(ns tvm-clj.ast
  "TVM's algorithms are first described using an AST tailored towards
  ND-array programming."
  (:require [tvm-clj.impl.protocols :refer [->node] :as tvm-proto]
            [tvm-clj.impl.node :as jna-node]
            [tvm-clj.impl.fns.te :as te-fns]
            [tvm-clj.impl.fns.tir :as tir-fns]
            [tvm-clj.impl.fns.ir :as ir-fns]
            [tvm-clj.ast.elemwise-op :as ast-op]
            [tech.v3.datatype :as dtype]
            [tech.v3.datatype.casting :as casting]
            [tech.v3.datatype.errors :as errors]
            [clojure.string :as s])
  (:refer-clojure :exclude [range]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


(defmacro when-not-error
  [condition throw-clause]
  `(when-not (do ~condition)
     (throw ~throw-clause)))


(defn ->dtype
  ^String [dtype-or-name]
  (jna-node/->dtype dtype-or-name))

(def ^:dynamic *varname-prefix* "")

(defn safe-str
  [str-name]
  (let [str-name (if (keyword? str-name)
                   (name str-name)
                   (str str-name))]
    (s/replace (str *varname-prefix* str-name) "-" "_")))


(defn variable
  "Create a scalar variable.  Returns a node handle"
  [^String name & {:keys [dtype span]
                   :or {dtype "int32" span nil}}]
  (tir-fns/Var (safe-str name) (->dtype dtype) span))


(defn placeholder
  "Create a user-supplied tensor variable"
  [shape name & {:keys [dtype]
                 :or {dtype "float32"}}]
  (let [shape (->> (if-not (instance? clojure.lang.Seqable shape)
                     [shape]
                     shape)
                   (mapv ->node))]
    (te-fns/Placeholder shape
                        (->dtype dtype)
                        (safe-str name))))


(defn- range
  "Create a range with defined start inclusive and end exclusive"
  ([start end]
    (range start end nil))
  ([start end span]
    (ir-fns/Range start end span)))



(defn tget
  "Get an item from a tensor"
  ([tensor indices]
   (tget tensor indices nil))
  ([tensor indices span]
   (let [indices (if (number? indices)
                   [indices]
                   indices)]
     (when-not-error (= (count (:shape tensor))
                       (count indices))
       (ex-info "Num indices must match tensor rank"
         {:tensor-range (count (:shape tensor))
          :index-count (count indices)}))
     (let [indices
           (mapv (fn [index-val]
                   (let [node-data (->node index-val)
                         node-type-name (tvm-proto/node-type-name node-data)]
                     (if (= "tir.IterVar" node-type-name)
                       (:var node-data)
                       node-data)))
             indices)]
       (tir-fns/ProducerLoad tensor indices span)))))


(defmacro tvm-let
  "Lets in tvm must be nested.  This leads to an exciting macro.
  Pairs must be of the form var val-expr.  Body is *not* an implicit
  do!!"
  [expr-pairs body]
  (->> expr-pairs
       (partition 2)
       reverse
       (reduce (fn [data [var-symbol expr]]
                 `(let [evaled-expr# ~expr
                        ~var-symbol (variable ~(safe-str var-symbol)
                                              :dtype (:dtype evaled-expr#))]
                    (tir-fns/Let ~var-symbol evaled-expr# ~data)))
               body)))


; https://github.com/apache/tvm/blob/4cdbf5cbfec3db5b5ef5177a7611efecaf56d8c7/include/tvm/tir/var.h#L178
(def ^:private iteration-variable-types
  "Iteration variable types defined in tvm/include/Expr.h"
  {
   ;; /*!
   ;; * \brief Data parallel iteration.
   ;; *  This normally corresponds to axis of Tensor.
   ;; *  Allow all IterVar manipulations.
   ;; *
   ;; * \note This does not mean the loop
   ;; *  have to be executed in parallel fashion.
   ;; */
   :data-parallel 0
   ;; /*!
   ;; * \brief The IterVar itself is a thread-index
   ;; *  of a fixed thread launching group.
   ;; *  Note that this is already assumed to be parallelized.
   ;; *
   ;; *  Disallow: split/fuse/vectorize/parallel
   ;; */
   :thread-index 1
   ;; /*!
   ;; * \brief Communicative reduction.
   ;; *  Cannot be directly parallelized.
   ;; *
   ;; *  Disallow: parallel/vectorize
   ;; */
   :communicative-reduce 2
   ;; /*!
   ;; * \brief Serial loops with loop carry dependency,
   ;; *  the iteration must execute in order.
   ;; *  Cannot be re-ordered.
   ;; *
   ;; *  Disallow: reorder/parallel/vectorize
   ;; */
   :ordered 3
   ;; /*!
   ;; * \brief IterVar is opaque,
   ;; *
   ;; *  May not corresponds to any generated loop
   ;; *  Disallow all IterVar manipulations and compute_at
   ;; *
   ;; * \note This is usually used to implement composite op
   ;; *  or external op, where the
   ;; */
   :dim-info 4
   ;; // The following are possible additional
   ;; // types that are provided during schedule
   ;; /*!
   ;; * \brief The execution is unrolled.
   ;; */
   :unrolled 5
   ;; /*!
   ;; * \brief The loop is vectorized.
   ;; */
   :vectorized 6
   ;; /*!
   ;; * \brief The loop is parallelized.
   ;; */
   :parallelized 7
   ;; /*!
   ;; * \brief Marks boundary of tensorization intrinsic.
   ;; */
   :tensorized 8})


(def iteration-variable-type-set (set (keys iteration-variable-types)))


(defn iteration-variable
  "Create a variable that controls iteration through the data.  The iteration type
affects the class of optimizations that the compiler is able to apply to the affected
expressions,

    Parameters
    ----------
    dom : Range
        The domain of iteration.

    name : str
        The name of iteration variable.

    iteration-type : keyword
        The type of iteration.

    thread-tag : str
        The thread tag of the iteration variable."
  [domain name iteration-type & {:keys [thread-tag span]
                                 :or {thread-tag "" span nil}}]

  (when-not-error (iteration-variable-type-set iteration-type)
    (ex-info "Iteration type not in allowed iteration types"
             {:allowed-types iteration-variable-type-set
              :iteration-type iteration-type}))

  (let [domain (when domain
                 (if (and (tvm-proto/is-node-handle? domain)
                          (= :range (tvm-proto/node-type-name domain)))
                   domain
                   (range (first domain) (second domain))))
        v (variable name)]
    (tir-fns/IterVar domain v (iteration-variable-types iteration-type) thread-tag span)))


(defn name->thread-axis-iterator
  "Create a thread iter-var from a thread axis name"
  [axis-name]
  (iteration-variable nil axis-name :thread-index :thread-tag axis-name))


(defmacro tvm-fn
  "Like (fn) but retains the arglists.  Lambda in clojure unfortunately does not."
  [arg-vec & body]
  `(-> (fn ~arg-vec
         ~@body)
       (vary-meta assoc :arglists '(~arg-vec))))


(defn tvm-fn->args
  "Get the vector of tvm-safe string argument names to a tvm function."
  [tvm-fn]
  (if-let [retval (first (:arglists (meta tvm-fn)))]
    (mapv (comp safe-str name) retval)
    (errors/throwf "Function %s does not appear to have metadata associated with it.
Perhaps use `tvm-fn` to create the function as `fn` does not produce
proper metadata on the fn object."
                   tvm-fn)))


(defn compute-op
  "Construct a new tensor by computing over the shape domain.

    The compute rule is result[axis] = fcompute(axis)

    Parameters
    ----------
    shape: Array of Expr
        The shape of the tensor

    fcompute: lambda function of indices-> value
        Specifies the input source expression

    name: str, optional
        The name hint of the tensor

    tag: str, optional
        Additonal tag information about the compute.

    attrs: dict, optional
        The additional auxiliary attributes about the compute.

    Returns
    -------
    The created compute node
    "
  ([shape name {:keys [tag attrs]
                 :or {tag ""}}
    fcompute]
   (let [fn-arglists (tvm-fn->args fcompute)]
     (when-not-error fn-arglists
       (ex-info "Functions passed into compute must have the arglists in their metadata"
                {}))
     (when-not-error (= (count shape)
                        (count fn-arglists))
       (ex-info "fcompute must have same number of args as rank of shape"
                {:shape-rank (count shape)
                 :num-fn-args (count fn-arglists)}))
     (let [compute-dim (map (fn [arg-name shape-value]
                              (let [shape-value (if (sequential? shape-value)
                                                  shape-value
                                                  [0 shape-value])]
                                (iteration-variable shape-value arg-name
                                                    :data-parallel)))
                            fn-arglists shape)
           body-data (apply fcompute (map :var compute-dim))
           body-data (if-not (instance? clojure.lang.Sequential body-data)
                       [body-data]
                       body-data)]
       (te-fns/ComputeOp (safe-str name) tag attrs compute-dim body-data))))
  ([shape name fcompute]
   (compute-op shape name nil fcompute))
  ([shape fcompute]
   (compute-op shape "compute" nil fcompute)))


(defmacro compute
  "Compute a new tensor over this shape."
  ([shape name options idx-varnames body]
   `(compute-op
     ~shape ~name ~options
     (tvm-fn
      ~idx-varnames
      ~body)))
  ([shape name idx-varnames body]
   `(compute ~shape ~name nil ~idx-varnames ~body))
  ([shape idx-varnames body]
   `(compute ~shape "compute" nil ~idx-varnames ~body)))


(defn commutative-reducer
  "Create a commutative reducer.   Reducers are used in
  as the part of the commutative reduction pathway.

  * `reduce-fn-args` - sequence of maps of {:name :datatype :argument-type :identity-value}
    tell you the name of the argument, the datatype, and when the argument
    is and `:accumulating` arg or an `:incoming` arg.  If the argument is an accumulator argument
    then an `:identity-value` must be provided.
  * `incoming-names` - Argument names of the incoming values.
  * `reduction-ast-fn` - fn taking '(+ (count accum-args) (count incoming-args))'
    arguments and returning an AST that performs the reduction.

  Returns a commutative reducer you can use in `Reduce`."
  [reduce-fn-args reduction-ast-fns]
  (let [accum-args (filterv #(= :accumulating (:argument-type %)) reduce-fn-args)
        incoming-args (filterv #(= :incoming (:argument-type %)) reduce-fn-args)]
    (errors/when-not-error
     (not= (count accum-args) 0)
     "No accumulating arguments provided")
    (errors/when-not-error
     (not= (count incoming-args) 0)
     "No incoming arguments provided")
    (let [var-fn #(assoc % :variable (variable (:name %) :dtype (:datatype %)))
          accum-args (mapv var-fn accum-args)
          incoming-args (mapv var-fn incoming-args)
          argmap (->> (concat accum-args incoming-args)
                      (map (juxt :name :variable))
                      (into {}))
          span nil]
      (tir-fns/CommReducer (mapv :variable accum-args)
                           (mapv :variable incoming-args)
                           (->> reduction-ast-fns
                                (map #(let [retval (apply % (map (comp argmap :name) reduce-fn-args))]
                                        (if (sequential? retval)
                                          retval
                                          [retval])))
                                (apply concat)
                                vec)
                           ;;Identity values, one for each accumulator
                           (mapv (fn [{:keys [datatype identity-value]}]
                                   (errors/when-not-error
                                    identity-value
                                    "Identity values must be provided for every accumulator")
                                   (if (number? identity-value)
                                     (jna-node/const identity-value datatype)
                                     (tvm-proto/->node identity-value)))
                                 accum-args) span))))


(defn tvm-fn->commutative-reducer
  "Make a reducer out of a tvm function assuming all arguments are the
  same datatype.

  Accumulation arguments are considered the first N arguments where N
  is the number of initial values.  The rest of the arguments are considered
  incoming arguments.  There must be at least one each of accumulation and
  incoming arguments.

  * `tvm-fn` - a function with proper metadata such that `:arglists` can be found.
  * `identity-values` - list of identity values.  This implicitly indicates the
    number of accumulation arguments as there must be one identity value per
    accumulation arguments.
  * `datatype` - Option datatype.  If not provided will be inferred from the
  datatypes of identity-values."
  ([tvm-fn identity-values datatypes]
   (let [arglist (tvm-fn->args tvm-fn)
         n-accum-args (count identity-values)
         accum-args (take n-accum-args arglist)
         incoming-args (drop n-accum-args arglist)]
     (commutative-reducer
      (concat (map (fn [argname identity-value datatype]
                     {:name argname
                      :argument-type :accumulating
                      :datatype datatype
                      :identity-value identity-value})
                   accum-args identity-values datatypes)
              (map (fn [argname datatype]
                     {:name argname
                      :argument-type :incoming
                      :datatype datatype})
                   incoming-args datatypes))
      [tvm-fn])))
  ([tvm-fn identity-values]
   (tvm-fn->commutative-reducer tvm-fn identity-values
                                (map (fn [item]
                                       (dtype/elemwise-datatype item))
                                     identity-values))))


(defn- ->comm-reducer
  [reducer-or-tuple]
  (if (sequential? reducer-or-tuple)
    (let [[reduce-op dtype] reducer-or-tuple]
      (case reduce-op
        :+ (tvm-fn->commutative-reducer
            (tvm-fn
             [lhs rhs]
             (ast-op/+ lhs rhs))
            [(ast-op/const 0 dtype)])
        :min (tvm-fn->commutative-reducer
              (tvm-fn
               [lhs rhs]
               (ast-op/min lhs rhs))
              [(ast-op/max-value dtype)])
        :max (tvm-fn->commutative-reducer
              (tvm-fn
               [lhs rhs]
               (ast-op/max lhs rhs))
              [(ast-op/min-value dtype)])))
    reducer-or-tuple))


(defn commutative-reduce
  "Create a reduce node.

  * `comm-reducer` - A commutative reducer produced via either `commutative-reducer`
  or `tvm-fn->commutative-reducer`.
  * `reduce-axis` - A list of either maps of {:domain :name} or `iteration-variable`'s
     of type `:communicative-reduce`. If a list element is a map?, it will be interpreted
     as a map of {:domain :name} in the corresponding iteration variable will be created for you.

  * `read-exprs` - Either `fn?`'s or a list of expressions that must equal the number of
    inputs to `commutative-reducer` and that must be based off of the variables defined
    in `reduce-axis`.  If read-exprs are clojure 'fn?'s they will be called
    with the reduction variables created from reduce-axis."
  ([comm-reducer reduce-axis read-exprs condition span]
   (let [comm-reducer (->comm-reducer comm-reducer)
         reduce-axis (mapv (fn [axis-entry]
                             (if (map? axis-entry)
                               (do
                                 (errors/when-not-errorf
                                  (contains? axis-entry :domain)
                                  "Mising :domain key from axis argument %s" axis-entry)
                                 (iteration-variable (:domain axis-entry)
                                                     (:name axis-entry)
                                                     :communicative-reduce))
                               axis-entry))
                           reduce-axis)
         read-exprs (mapcat (fn [read-expr]
                              (if (fn? read-expr)
                                (let [result (apply read-expr reduce-axis)]
                                  (if (sequential? result)
                                    result
                                    [result]))
                                [read-expr]))
                            read-exprs)
         init (->node [])
         read-exprs (->node read-exprs)
         reduce-axis (->node reduce-axis)
         condition (->node (or (if (fn? condition)
                                 (apply condition reduce-axis)
                                 condition)
                               true))]
     (if (== 1 (count read-exprs))
       (tir-fns/Reduce comm-reducer read-exprs reduce-axis condition 0 init span)
       (mapv (fn [idx]
               (tir-fns/Reduce comm-reducer read-exprs reduce-axis condition (int idx) init span))
             (clojure.core/range (count read-exprs))))))
  ([comm-reducer reduce-axis read-exprs span]
   (commutative-reduce comm-reducer reduce-axis read-exprs nil span))
  ([comm-reducer reduce-axis read-exprs]
   (commutative-reduce comm-reducer reduce-axis read-exprs nil nil)))


(defn output-tensors
  [compute-op]
  (->> (clojure.core/range (te-fns/OpNumOutputs compute-op))
       (mapv (partial te-fns/OpGetOutput compute-op))))


(defn first-output
  [compute-op]
  (te-fns/OpGetOutput compute-op 0))


(defn input-tensors
  [compute-op]
  (te-fns/OpInputTensors compute-op))


(defn first-input
  [compute-op]
  (first (te-fns/OpInputTensors compute-op)))


(defn ->operation
  [tens-or-op]
  (if (= (tvm-proto/node-type-name tens-or-op) "Tensor")
    (:op tens-or-op)
    tens-or-op))


(defn scan
  "Create a recursive scan operation"
  [init-op update-op scan-state args & [{:keys [op-name tag attrs]
                                          :or {op-name "scan"
                                               tag ""}}]]
  (let [op-name (safe-str op-name)
        init-tensors (output-tensors init-op)
        update-tensors (output-tensors update-op)
        scan-state (if (sequential? scan-state)
                     scan-state
                     [scan-state])
        iter-var      (iteration-variable
                       [(first (:shape (first init-tensors)))
                        (first (:shape (first update-tensors)))]
                       (format "%s.idx" op-name) :ordered)]
    (te-fns/ScanOp op-name tag attrs iter-var
                   init-tensors update-tensors scan-state args)))

(ns tvm-clj.compile-test
  (:require [tvm-clj.compute.functional-tensor :as ct]
            [tvm-clj.compute.tensor.cpu-functional-tensor]
            ;;unsigned datatype support
            [tvm-clj.compute.host-buffer :as hbuf]
            [clojure.test :refer :all]
            [think.resource.core :as resource]
            [tech.datatype.base :as dtype]
            [tech.datatype.marshal :as marshal]
            [clojure.core.matrix.protocols :as mp]
            [tech.compute.tensor :as compute-tensor]
            [tech.compute.tensor.dimensions :as ct-dims]
            [tech.javacpp-datatype :as jcpp-dtype]
            [clojure.core.matrix.macros :refer [c-for]]
            [tvm-clj.compute.registry :as tvm-reg]
            [tvm-clj.api :as api]
            [tech.compute.driver :as drv]
            [think.parallel.core :as parallel]
            [clojure.core.matrix :as m]
            [tvm-clj.compute.cpu :as cpu]
            [tvm-clj.compute.tensor-math :as tvm-tm]
            [tvm-clj.base :as tvm-base])
  (:import [org.bytedeco.javacpp opencv_core
            opencv_imgcodecs opencv_core$Mat]
           [tech.datatype ByteArrayView FloatArrayView]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


(defn convert-bgr-bytes-to-floats
  "Input is unsigned bytes, so values 0->255, bgr [height width 3].
Output: {:datatype :float32 :shape [3 height width]}, values from -0.5->0.5"
  [input-tensor]
  ;;Move to bgr instead of rgb  Also drop alpha if exists in first place.
  (-> (ct/select input-tensor :all :all [2 1 0])
      ;;Image is now planar; so a plane of b, a plane of g, and a plane of r.
      (ct/transpose [2 0 1])
      ;;First actual operation that is compiled.
      (ct/static-cast :float32)
      ;;Rest of the work.  These should all get rolled into assignment step above.
      (ct/div 255.0)
      (ct/sub 0.5)))


(defn convert-bgr-bytes-to-floats-by-hand
  [input-tensor result-tensor]
  (let [^bytes ary-data (.data ^ByteArrayView (:buffer input-tensor))
        [height width n-channels] (ct/shape input-tensor)
        height (long height)
        width (long width)
        n-channels (long n-channels)
        n-elems (long (* height width 3))
        ^floats retval (.data ^FloatArrayView (:buffer result-tensor))]
    (parallel/parallel-for idx n-elems
                           (let [pixel (quot idx n-channels)
                                 channel (rem idx n-channels)
                                 x-pos (rem pixel width)
                                 y-pos (quot pixel width)
                                 dest-channel-stride (* x-pos y-pos)]
                             (when (< channel 3)
                               (aset retval (+ pixel
                                               (* (- 2 channel) dest-channel-stride))
                                     ;;Make up for lack of unsigned byte support with
                                     ;;bit-and, casting
                                     (float (- (/ (-> (aget ary-data idx)
                                                      int
                                                      (bit-and 0xFF))
                                                  255.0)
                                               0.5))))))
    result-tensor))


(defn bgr-bytes-custom-tvm
  [driver]
  (let [img-width (api/variable "image-width")
        img-height (api/variable "image-height")
        n-channels (api/variable "n-channels")
        input-tensor (api/placeholder [img-height img-width n-channels] "input-tensor" :dtype :uint8)
        operation (api/compute
                   [(api/min n-channels (int 3)) img-height img-width]
                   (api/tvm-fn
                    [chan y x]
                    (-> (api/tget input-tensor [y x (api/sub 2 chan)])
                        (api/cast :float32)
                        (api/div (float 255.0))
                        (api/sub (float 0.5))))
                   "bgr-types-op")

        schedule (api/create-schedule [operation])
        [chan-axis y-axis x-axis] (:axis operation)
        op-stage (api/->stage schedule operation)
        [y-outer x-outer y-inner x-inner] (api/stage-tile op-stage y-axis x-axis 32 32)
        arglist [input-tensor (first (api/output-tensors operation))]]
    (api/stage-vectorize op-stage x-inner)
    (if (= :cpu (tvm-reg/device-type-kwd driver))
      (api/stage-parallel op-stage chan-axis)
      (api/bind-gpu-axis op-stage
                         [chan-axis y-outer x-outer]
                         [y-inner x-inner]))
    ;;There are a lot of assumptions in this step.  We aren't providing a bind-map which means we expect
    ;;input to be dense and simple input dimensions
    (println (api/schedule->str schedule arglist :bgr-convert))
    (tvm-reg/schedule->fn driver {:schedule schedule
                                  :arglist arglist
                                  :name :bgr-convert})))



(defn tensor-take
  [n tensor]
  (-> (compute-tensor/as-vector tensor)
      (compute-tensor/select (range n))
      (compute-tensor/to-double-array)
      vec))


(extend-type opencv_core$Mat
  resource/PResource
  (release-resource [item] (.release item))
  mp/PDimensionInfo
  (dimensionality [m] (count (mp/get-shape m)))
  (get-shape [m] [(.rows m) (.cols m) (.channels m)])
  (is-scalar? [m] false)
  (is-vector? [m] true)
  (dimension-count [m dimension-number]
    (let [shape (mp/get-shape m)]
      (if (<= (count shape) (long dimension-number))
        (get shape dimension-number)
        (throw (ex-info "Array does not have specific dimension"
                        {:dimension-number dimension-number
                         :shape shape})))))
  mp/PElementCount
  (element-count [m] (apply * (mp/get-shape m)))

  dtype/PDatatype
  ;;For now; I know opencv has more datatypes but whatevs
  (get-datatype [m] :uint8)

  marshal/PContainerType
  (container-type [item] :tvm-host-buffer)

  ;;This allows bulk read/write into the object
  hbuf/PToPtr
  (->ptr [item] (jcpp-dtype/set-pointer-limit-and-capacity
                 (.ptr item)
                 (mp/element-count item)))

  dtype/PCopyRawData
  (copy-raw->item! [item dest dest-offset]
    (marshal/copy! item 0 dest dest-offset (mp/element-count item))
    [dest (+ (long dest-offset) (long (mp/element-count item)))]))


(defn opencv-mat->tensor
  [mat]
  (let [device (drv/get-device compute-tensor/*stream*)]
    (if (= :cpu (-> (tvm-reg/device-type-kwd device)))
      ;;For cpu, we have a direct, zero-copy conversion.  We can read/write directly to
      ;;opencv's data storage
      (-> mat
          hbuf/->ptr
          (cpu/ptr->device-buffer :dtype (dtype/get-datatype mat))
          (tvm-tm/device-buffer->tensor (ct-dims/dimensions (m/shape mat))))
      (compute-tensor/->tensor mat :datatype (dtype/get-datatype mat)))))



(defn load-image
  [^String filepath]
  (resource/track (opencv_imgcodecs/imread filepath)))


(defn java-tensor-ops-image-test
  []
  (resource/with-resource-context
    (let [mat (load-image "test/data/jen.jpg")
          img-tensor (compute-tensor/->tensor mat :datatype :int8)
          result-tensor (compute-tensor/new-tensor (concat [3]
                                                           (take 2 (mp/get-shape mat)))
                                                   :datatype :float32
                                                   :init-value nil)]
      (convert-bgr-bytes-to-floats img-tensor)
      (time (convert-bgr-bytes-to-floats img-tensor)))))


(defn java-by-hand-image-test
  []
  (resource/with-resource-context
    (let [mat (load-image "test/data/jen.jpg")
          img-tensor (compute-tensor/->tensor mat :datatype :int8)
          result-tensor (compute-tensor/new-tensor (concat [3]
                                                           (take 2 (mp/get-shape mat)))
                                                   :datatype :float32
                                                   :init-value nil)
          time-str (with-out-str
                     (time (convert-bgr-bytes-to-floats-by-hand img-tensor
                                                                result-tensor)))]

      {:result (tensor-take 10 result-tensor)
       :correct (->> (tensor-take 30 img-tensor)
                     (partition 3)
                     (map last)
                     (map #(/ (double (bit-and (int %) 0xFF)) 255.0))
                     (map #(- (double %) 0.5)))
       :time time-str})))


(defn tvm-image-test
  [dev-type]
  (resource/with-resource-context
    (compute-tensor/with-stream (drv/default-stream
                                 (tvm-reg/get-device dev-type 0))
      (compute-tensor/with-datatype
        :float32
        (let [mat (load-image "test/data/jen.jpg")
              ;;It would also be possible to do a zero-copy conversion using the
              ;; opencl matrix ptr.
              ;;Note that tvm supports unsigned datatypes.

              img-tensor (opencv-mat->tensor mat)
              result-tensor (compute-tensor/new-tensor
                             (concat [3]
                                     (take 2 (mp/get-shape mat)))
                             :datatype :float32
                             :init-value nil)
              ;; result-tensor (compute-tensor/new-tensor (mp/get-shape mat)
              ;;                                          :datatype :float32
              ;;                                          :init-value nil)
              tvm-convert-fn (bgr-bytes-custom-tvm (drv/get-driver compute-tensor/*stream*))
              ;;This is abit careless but I know the results of the compilation process
              ;;run fn twice because some platforms delay some compilation
              _ (tvm-convert-fn img-tensor result-tensor)
              time-str (with-out-str
                         (time
                          (do
                            (tvm-convert-fn img-tensor result-tensor)
                            (drv/sync-with-host compute-tensor/*stream*))))]
          {:result (vec (tensor-take 10 result-tensor))
           :correct (->> (tensor-take 30 img-tensor)
                         (partition 3)
                         (map last)
                         (map #(/ (double %) 255.0))
                         (mapv #(- (double %) 0.5)))
           :time time-str})))))


(defn- is-correct
  [{:keys [result correct] :as retval}]
  (is (m/equals result correct 0.001))
  retval)


(defn- run-test
  [test-fn]
  (-> (test-fn)
      is-correct
      :time))


(deftest time-tests
  []
  (println "hand-coded java took: " (run-test #(java-by-hand-image-test)))

  (println "Compiled (cpu) tensor took:" (run-test #(tvm-image-test :cpu)))

  (println "Compiled (opencl) tensor took:" (run-test #(tvm-image-test :opencl))))

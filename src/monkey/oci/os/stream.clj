(ns monkey.oci.os.stream
  "Allows writing an object using multiparts as a stream.  Useful
   for large files and logging."
  (:require [clojure.tools.logging :as log]
            [manifold
             [deferred :as md]
             [stream :as ms]]
            [monkey.oci.os.martian :as m]))

(defn stream->multipart
  "Returns a Manifold `stream` that will send each message received to 
   a multipart upload.  When the stream is closed, the multipart is 
   committed. In order to abort the upload, send the `cancel` token."
  [ctx opts]
  (md/chain
   ;; Create a multipart object
   ;; TODO Handle errors
   (m/create-multipart-upload ctx (-> opts
                                      (select-keys [:ns :bucket-name])
                                      (assoc :multipart {:object (:object-name opts)})))
   :body
   (fn [{:keys [upload-id bucket namespace object]}]
     (let [etags (atom [])
           opts {:ns namespace
                 :bucket-name bucket
                 :object-name object
                 :upload-id upload-id}
           
           commit
           (fn []
             @(m/commit-multipart-upload
               ctx
               (assoc opts
                      :multipart {:parts-to-commit @etags})))
           
           collect-or-abort
           (fn [idx {:keys [status headers body]}]
             (some?
              (if (= 200 status)
                ;; Collect the etags and part numbers, we'll need them when committing
                (swap! etags conj (-> headers
                                      (select-keys [:etag])
                                      (assoc :part-num idx)))
                (do
                  (log/error "Unable to upload part, got status" status ":" (:message body))
                  @(m/abort-multipart-upload ctx opts)))))
           
           s (doto (ms/stream)
               (ms/on-drained commit))
           idx (doto (ms/stream)
                 (ms/put-all! (range 1 (inc m/max-multipart-count))))]
       ;; Take items from the stream and join them with an index
       ;; TODO Handle the case where max number of parts is reached
       (->> s
            (ms/map (comp (partial zipmap [:idx :part]) vector) idx)
            (ms/consume-async
             (fn [{:keys [idx part]}]
               (md/chain
                (m/upload-part ctx
                               (assoc opts
                                      :upload-part-num idx
                                      :part part))
                ;; Collect the etags, or abort the upload on error
                (partial collect-or-abort idx)))))
       s))))

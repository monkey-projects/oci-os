(ns monkey.oci.os.stream
  "Allows writing an object using multiparts as a stream.  Useful
   for large files and logging."
  (:require [clojure.tools.logging :as log]
            [manifold
             [deferred :as md]
             [stream :as ms]]
            [medley.core :as mc]
            [monkey.oci.os.martian :as m]))

(defn- create-multipart [ctx opts]
  (log/debug "Creating multipart with options:" opts)
  (m/create-multipart-upload
   ctx
   (-> opts
       (select-keys [:ns :bucket-name])
       (assoc :multipart (-> (select-keys opts [:metadata])
                             (assoc :object (:object-name opts)))))))

(defn- committer
  ([ctx opts]
   (fn [etags]
     (log/debug "Committing multipart with" (count etags) "parts")
     (m/commit-multipart-upload
      ctx
      (assoc opts
             :multipart {:parts-to-commit etags}))))
  ([ctx opts etags]
   (let [c (committer ctx opts)]
     (fn []
       (c @etags)))))

(defn- collector [aborter etags]
  (fn [idx {:keys [status headers body]}]
    (cond
      (= status 200)
      ;; Collect the etags and part numbers, we'll need them when committing
      (do
        (swap! etags conj (-> (select-keys headers [:etag])
                              (assoc :part-num idx)))
        true)
      (>= status 400)
      (do
        (log/error "Unable to upload part, got status" status ":" (:message body))
        (md/chain
         (aborter)
         (constantly false)))
      :else ; Nothing to do
      true)))

(defn- body-or-throw-on-error [{:keys [status] :as r}]
  (if (>= status 400)
    (throw (ex-info "Unable to create multipart upload" r))
    (:body r)))

(defn stream->multipart
  "Returns a Manifold `stream` that will send each message received to 
   a multipart upload.  When the stream is closed, the multipart is 
   committed. In order to abort the upload, send the `cancel` token."
  [ctx opts]
  (md/chain
   (create-multipart ctx opts)
   body-or-throw-on-error
   (fn [{:keys [upload-id bucket namespace object]}]
     (let [etags (atom [])
           opts {:ns namespace
                 :bucket-name bucket
                 :object-name object
                 :upload-id upload-id}

           commit (comp deref (committer ctx opts etags))
           aborter #(m/abort-multipart-upload ctx opts)
           collect-or-abort (collector aborter etags)
           
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

(defn- shrink-buf
  "Returns a new array of size `n` with bytes from `buf` copied in.  If
   `n` is the same as the size of `buf`, just returns `buf`."
  [buf n]
  (if (< n (count buf))
    (let [o (byte-array n)]
      (System/arraycopy buf 0 o 0 n)
      o)
    buf))

(defn input-stream->multipart
  "Pipes an input stream to a multipart upload.  When the stream closes,
   the multipart is committed.  `opts` requires the properties needed to
   create a multipart (ns, bucket-name and object-name), and also `input-stream`,
   which is the stream to read from.  If `close?` is `true`, the input
   stream is closed when the upload aborts."
  [ctx {in :input-stream
        :as opts
        :keys [content-type close? buf-size progress]
        :or {content-type "application/binary"
             buf-size 0x10000}}]
  ;; TODO Refactor to improve testability
  (md/chain
   (create-multipart ctx opts)
   body-or-throw-on-error
   (fn [{:keys [upload-id bucket namespace object]}]
     (let [buf (byte-array buf-size)
           etags (atom [])
           opts {:ns namespace
                 :bucket-name bucket
                 :object-name object
                 :upload-id upload-id}
           commit (committer ctx opts etags)]
       (letfn [(maybe-close [r]
                 (when close?
                   (.close in))
                 r)
               (abort []
                 (md/chain
                  (m/abort-multipart-upload ctx opts)
                  maybe-close))
               (commit-or-abort [_]
                 ;; It's not possible to commit an empty multipart stream
                 (if (empty? @etags)
                   (abort)
                   (commit)))
               (read [offs len]
                 (md/future
                   (try
                     (.read in buf offs len)
                     (catch java.io.IOException ex
                       (if (= "Pipe closed" (.getMessage ex))
                         -1
                         (throw ex))))))
               (upload-part [idx n]
                 (if (pos? n)
                   (do
                     (log/trace "Uploading" n "bytes as part" idx)
                     (m/upload-part ctx
                                    (assoc opts
                                           :upload-part-num idx
                                           :headers {:content-type content-type}
                                           :part (shrink-buf buf n))))
                   {:status 204}))
               (report-progress [res idx total]
                 (when progress
                   (progress {:opts (assoc opts :upload-id upload-id)
                              :progress {:idx (dec idx)
                                         :total-bytes total}}))
                 res)
               (upload-and-collect [idx n total]
                 (md/chain
                  (upload-part idx n)
                  #(report-progress % idx total)
                  (partial (collector abort etags) idx)))]
         (md/loop [idx 1
                   offs 0
                   total 0]
           (md/chain
            (read offs (- buf-size offs))
            (fn [n]
              (log/trace "Read" n "bytes from input stream")
              (let [total+ (+ total n)]
                (cond
                  (neg? n)
                  ;; EOF, upload remaining part and commit
                  (md/chain
                   (upload-and-collect idx offs total)
                   commit-or-abort)
                  
                  (= buf-size (+ offs n))
                  ;; Buffer is full, upload it and proceed to next part
                  (md/chain
                   (upload-and-collect idx buf-size total+)
                   (fn [c?]
                     (when c?
                       (md/recur (inc idx) 0 total+))))

                  :else
                  ;; Buffer not full yet, read more data
                  (md/recur idx (+ offs n) total+)))))))))))

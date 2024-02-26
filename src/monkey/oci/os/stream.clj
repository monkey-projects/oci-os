(ns monkey.oci.os.stream
  "Allows writing an object using multiparts as a stream.  Useful
   for large files and logging."
  (:require [clojure.tools.logging :as log]
            [manifold
             [deferred :as md]
             [stream :as ms]]
            [monkey.oci.os.martian :as m]))

(defn- create-multipart [ctx opts]
  (m/create-multipart-upload ctx (-> opts
                                     (select-keys [:ns :bucket-name])
                                     (assoc :multipart {:object (:object-name opts)}))))

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
    (if (= 200 status)
      ;; Collect the etags and part numbers, we'll need them when committing
      (do
        (swap! etags conj (-> headers
                              (select-keys [:etag])
                              (assoc :part-num idx)))
        true)
      (do
        (log/error "Unable to upload part, got status" status ":" (:message body))
        (md/chain
         (aborter)
         (constantly false))))))

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
           commit (committer ctx opts etags)
           maybe-close (fn [r]
                         (when close?
                           (.close in))
                         r)
           abort (fn []
                   (md/chain
                    (m/abort-multipart-upload ctx opts)
                    maybe-close))
           collect-or-abort (collector abort etags)
           commit-or-abort (fn []
                             ;; It's not possible to commit an empty multipart stream
                             (if (empty? @etags)
                               (abort)
                               (commit)))
           read #(md/future
                   (try
                     (.read in buf)
                     (catch java.io.IOException ex
                       (if (= "Pipe closed" (.getMessage ex))
                         -1
                         (throw ex)))))]
       (md/loop [n (read)
                 idx 1
                 total 0]
         (md/chain
          n
          (fn [n]         
            (if (neg? n)
              ;; EOF
              (commit-or-abort)
              (md/chain
               (do
                 ;; TODO Allow grouping of small buffers into a larger part
                 (log/debug "Read" n "bytes, uploading them as part" idx)
                 (m/upload-part ctx
                                (assoc opts
                                       :upload-part-num idx
                                       :headers {:content-type content-type}
                                       :part (shrink-buf buf n))))
               (fn [res]
                 (when progress
                   (progress {:opts (assoc opts :upload-id upload-id)
                              :progress {:idx (dec idx)
                                         :total-bytes (+ total n)}}))
                 res)
               (partial collect-or-abort idx)
               (fn [c?]
                 (when c?
                   (md/recur (read) (inc idx) (+ total n)))))))))))))

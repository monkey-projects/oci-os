(ns user
  (:require [clojure.java.io :as io]
            [clj-commons.byte-streams :as bs]
            [clojure.tools.logging :as log]
            [config.core :refer [env]]
            [manifold.deferred :as md]
            [monkey.oci.os
             [core :as c]
             [martian :as m]
             [stream :as s]]
            [monkey.oci.common.utils :as u]))

(def conf (-> env
              (select-keys [:user-ocid :tenancy-ocid :key-fingerprint :private-key :region])
              (update :private-key u/load-privkey)))

(def cid (:compartment-ocid env))

(def ctx (c/make-client conf))

(def bucket-ns (delay @(c/get-namespace ctx)))
(def bucket-name "test-dev")
;;(def bucket-name "monkeyci-workspaces")

(defn used-mem []
  (let [rt (java.lang.Runtime/getRuntime)]
    (- (.totalMemory rt) (.freeMemory rt))))

(defn with-mem* [f]
  (java.lang.System/gc)
  (let [before (used-mem)]
    (try
      (f)
      (finally
        (let [diff (- (used-mem) before)]
          (log/infof "Memory difference: %d bytes (%.2f MB)" diff (float (/ diff 1048576))))))))

(defmacro with-mem [& body]
  `(with-mem*
     (fn []
       ~@body)))

(defn list-objects [& [opts]]
  (log/info "Listing objects in" bucket-name)
  @(c/list-objects ctx (merge {:ns @bucket-ns :bucket-name bucket-name} opts)))

(defn get-object [obj]
  (log/info "Retrieving object" obj)
  @(c/get-object ctx {:ns @bucket-ns :bucket-name bucket-name :object-name obj}))

(defn download-object [obj dest]
  @(md/chain
    (c/get-object ctx {:ns @bucket-ns :bucket-name bucket-name :object-name obj})
    #_#(io/copy % dest :buffer-size 0x100000)
    #(bs/transfer % dest {:append? false})))

(defn put-object [obj contents]
  (log/info "Putting object" obj)
  @(c/put-object ctx {:ns @bucket-ns :bucket-name bucket-name :object-name obj
                      :contents contents
                      :martian.core/request {:headers {"Content-Type" "text/plain"}}}))

(defn put-multipart
  "Uploads large file using multipart"
  [obj path & [opts]]
  (letfn [(show-progress [{:keys [progress]}]
            (log/info "Bytes uploaded:" (:total-bytes progress)))]
    (log/info "Uploading multipart:" path "to" obj)
    @(s/input-stream->multipart ctx (merge {:ns @bucket-ns
                                            :bucket-name bucket-name
                                            :object-name obj
                                            :input-stream (io/input-stream (io/file path))
                                            :close? true
                                            :progress show-progress}
                                           opts))))

(defn delete-object [obj]
  (log/info "Deleting object" obj)
  @(c/delete-object ctx {:ns @bucket-ns :bucket-name bucket-name :object-name obj}))

(defn rename-object [from to]
  (log/info "Renaming:" from "->" to)
  @(c/rename-object ctx
                    {:ns @bucket-ns
                     :bucket-name bucket-name
                     :rename {:source-name from
                              :new-name to}}))

(defn head-object [obj]
  @(c/head-object ctx {:ns @bucket-ns :bucket-name bucket-name :object-name obj}))

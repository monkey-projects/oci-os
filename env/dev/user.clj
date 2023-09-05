(ns user
  (:require [clojure.tools.logging :as log]
            [config.core :refer [env]]
            [monkey.oci.os
             [core :as c]
             [utils :as u]]))

(def conf (-> env
              (select-keys [:user-ocid :tenancy-ocid :key-fingerprint :private-key :region])
              (update :private-key u/load-privkey)))

(def cid (:compartment-ocid env))

(def ctx (c/make-context conf))

(def bucket-ns (delay @(c/get-namespace ctx)))
(def bucket-name "test-dev")

(defn list-objects []
  (log/info "Listing objects in" bucket-name)
  @(c/list-objects ctx {:ns @bucket-ns :bucket-name bucket-name}))

(defn get-object [obj]
  (log/info "Retrieving object" obj)
  @(c/get-object ctx {:ns @bucket-ns :bucket-name bucket-name :object-name obj}))

(defn put-object [obj contents]
  (log/info "Putting object" obj)
  @(c/put-object ctx {:ns @bucket-ns :bucket-name bucket-name :object-name obj
                      :contents contents
                      :martian.core/request {:headers {"Content-Type" "text/plain"}}}))

(defn delete-object [obj]
  @(c/delete-object ctx {:ns @bucket-ns :bucket-name bucket-name :object-name obj}))

(defn rename-object [from to]
  (log/info "Renaming:" from "->" to)
  @(c/rename-object ctx
                    {:ns @bucket-ns
                     :bucket-name bucket-name
                     :rename {:source-name from
                              :new-name to}}))

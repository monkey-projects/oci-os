(ns user
  (:require [config.core :refer [env]]
            [monkey.oci.os
             [core :as c]
             [utils :as u]]))

(def conf (-> env
              (select-keys [:user-ocid :tenancy-ocid :key-fingerprint :private-key :region])
              (update :private-key u/load-privkey)))

(def cid (:compartment-ocid env))

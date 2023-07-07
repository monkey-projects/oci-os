(ns monkey.oci.os.core
  (:require [camel-snake-kebab.core :as csk]
            [clojure.data.json :as json]
            [manifold.deferred :as md]
            [monkey.oci.sign :as sign]
            [org.httpkit.client :as http]))

(defn- host [{:keys [region]}]
  (format "https://objectstorage.%s.oraclecloud.com" region))

(defn- make-url [conf path]
  (str (host conf) path))

(defn- parse-body [b]
  (json/read-str b :key-fn csk/->kebab-case-keyword))

(defn- execute-request
  "Executes a request with security headers generated from the config.
   Returns a manifold `deferred`."
  [conf req]
  (let [sign-headers (sign/sign-headers req)
        headers (sign/sign conf sign-headers)
        d (md/deferred)]
    (http/request (update req :headers merge headers)
                  (fn [{:keys [error] :as resp}]
                    (if error
                      (md/error! d resp)
                      (md/success! d resp))))
    (md/chain
     d
     :body
     parse-body)))

(defn- get! [conf path]
  (execute-request conf
                   {:url (make-url conf path)
                    :method :get}))

(defn get-namespace
  "Retrieves the bucket namespace associated with the tenancy"
  [conf]
  (get! conf "/n"))

(defn list-buckets
  "Lists all buckets for the given namespace and compartment id"
  [conf ns cid]
  (get! conf (format "/n/%s/b?compartmentId=%s" ns cid)))

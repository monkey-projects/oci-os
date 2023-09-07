(ns monkey.oci.os.martian
  "Low level functionality that uses Martian and Httpkit to send HTTP requests."
  (:require [martian
             [core :as martian]
             [httpkit :as martian-http]
             [interceptors :as mi]]
            [monkey.oci.os.utils :as u]
            [monkey.oci.sign.martian :as sm]
            [schema.core :as s]))

(set! *warn-on-reflection* true)

(def bucket-path ["/n/" :ns "/b/" :bucketName])
(def bucket-path-schema {:ns s/Str :bucketName s/Str})

(def object-path (concat bucket-path ["/o/" :objectName]))
(def object-path-schema (assoc bucket-path-schema :objectName s/Str))

(def routes
  [{:route-name :get-namespace
    :method :get
    :path-parts ["/n"]}
   
   {:route-name :list-buckets
    :method :get
    :path-parts ["/n/" :ns "/b"]
    :path-schema {:ns s/Str}
    :query-schema {:compartmentId s/Str}
    :produces #{"application/json"}}
   
   {:route-name :get-bucket
    :method :get
    :path-parts bucket-path
    :path-schema bucket-path-schema
    :produces #{"application/json"}}

   {:route-name :list-objects
    :method :get
    :path-parts (conj bucket-path "/o")
    :path-schema bucket-path-schema
    :query-schema {(s/optional-key :prefix) s/Str
                   (s/optional-key :start) s/Str
                   (s/optional-key :end) s/Str
                   (s/optional-key :limit) s/Int
                   (s/optional-key :delimiter) s/Str
                   (s/optional-key :fields) s/Str
                   (s/optional-key :startAfter) s/Str}
    :produces #{"application/json"}}

   {:route-name :put-object
    :method :put
    :path-parts object-path
    :path-schema object-path-schema
    :body-schema {:contents s/Any}}

   {:route-name :get-object
    :method :get
    :path-parts object-path
    :path-schema object-path-schema}
   
   {:route-name :delete-object
    :method :delete
    :path-parts ["/n/" :ns "/b/" :bucketName "/o/" :objectName]
    :path-schema {:ns s/Str :bucketName s/Str :objectName s/Str}}

   {:route-name :head-object
    :method :head
    :path-parts ["/n/" :ns "/b/" :bucketName "/o/" :objectName]
    :path-schema {:ns s/Str :bucketName s/Str :objectName s/Str}}

   {:route-name :rename-object
    :method :post
    :path-parts (conj bucket-path "/actions/renameObject")
    :path-schema bucket-path-schema
    :body-schema {:rename {:sourceName s/Str
                           :newName s/Str
                           (s/optional-key :srcObjIfMatchETag) s/Str
                           (s/optional-key :newObjIfMatchETag) s/Str
                           (s/optional-key :newObjIfNoneMatchETag) s/Str}}
    :consumes #{"application/json"}
    :produces #{"application/json"}}

   {:route-name :copy-object
    :method :post
    :path-parts (conj bucket-path "/actions/copyObject")
    :path-schema bucket-path-schema
    :body-schema {:copy {:sourceObjectName s/Str
                         (s/optional-key :sourceObjIfMatchETag) s/Str
                         (s/optional-key :sourceVersionId) s/Str
                         :destinationBucket s/Str
                         :destinationNamespace s/Str
                         :destinationObjectName s/Str
                         :destinationRegion s/Str
                         (s/optional-key :destinationObjectIfMatchETag) s/Str
                         (s/optional-key :destinationObjectIfNoneMatchETag) s/Str
                         (s/optional-key :destinationObjectMetadata) s/Any
                         (s/optional-key :destinationObjectStorageTier) s/Str}}
    :consumes #{"application/json"}
    :produces #{"application/json"}}])


(def host (comp (partial format "https://objectstorage.%s.oraclecloud.com") :region))

(defn make-context
  "Creates Martian context for the given configuration.  This context
   should be passed to subsequent requests."
  [conf]
  (letfn [(exclude? [{:keys [handler]}]
            (and (= :put (:method handler))
                 (= :put-object (:route-name handler))))]
    (martian/bootstrap
     (host conf)
     routes
     {:interceptors (concat martian/default-interceptors
                            [mi/default-encode-body
                             mi/default-coerce-response
                             (sm/signer (assoc conf :exclude-body? exclude?))
                             martian-http/perform-request])})))

(def send-request martian/response-for)

(u/define-endpoints *ns* routes martian/response-for)

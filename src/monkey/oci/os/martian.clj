(ns monkey.oci.os.martian
  "Low level functionality that uses Martian and Httpkit to send HTTP requests."
  (:require [camel-snake-kebab.core :as csk]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [martian
             [core :as martian]
             [encoders :as me]
             [interceptors :as mi]]
            [medley.core :as mc]
            [monkey.martian.aleph :as aleph]
            [monkey.oci.common
             [martian :as cm]
             [pagination :as p]
             [utils :as u]]
            [schema.core :as s]
            [tripod.context :as tc]))

(set! *warn-on-reflection* true)

(def bucket-path ["/n/" :ns "/b/" :bucket-name])
(def bucket-path-schema {:ns s/Str :bucket-name s/Str})

(def object-path (into bucket-path ["/o/" :object-name]))
(def object-path-schema (assoc bucket-path-schema :object-name s/Str))

(def json #{"application/json"})

(def storage-tier (s/constrained s/Str #{"Standard" "InfrequentAccess" "Archive"}))

(defn- between? [min max]
  (fn [v]
    (<= min v max)))

(s/defschema ListObjectsQuery
  {(s/optional-key :prefix) s/Str
   (s/optional-key :start) s/Str
   (s/optional-key :end) s/Str
   (s/optional-key :limit) s/Int
   (s/optional-key :delimiter) s/Str
   (s/optional-key :fields) s/Str
   (s/optional-key :start-after) s/Str})

(s/defschema PutObjectHeaders
  {(s/optional-key :content-type) s/Str
   (s/optional-key :storage-tier) storage-tier})

(s/defschema RenameObject
  {:source-name s/Str
   :new-name s/Str
   (s/optional-key :src-obj-if-match-e-tag) s/Str
   (s/optional-key :new-obj-if-match-e-tag) s/Str
   (s/optional-key :new-obj-if-none-match-e-tag) s/Str})

(s/defschema Metadata
  {s/Str s/Str})

(s/defschema CopyObject
  {:source-object-name s/Str
   (s/optional-key :source-obj-if-match-e-tag) s/Str
   (s/optional-key :source-version-id) s/Str
   :destination-bucket s/Str
   :destination-namespace s/Str
   :destination-object-name s/Str
   :destination-region s/Str
   (s/optional-key :destination-object-if-match-e-tag) s/Str
   (s/optional-key :destination-object-if-none-match-e-tag) s/Str
   (s/optional-key :destination-object-metadata) Metadata
   (s/optional-key :destination-object-storage-tier) s/Str})

(s/defschema CreateMultipartUpload
  {:object s/Str
   (s/optional-key :cache-control) s/Str
   (s/optional-key :content-disposition) s/Str
   (s/optional-key :content-encoding) s/Str
   (s/optional-key :content-language) s/Str
   (s/optional-key :metadata) Metadata
   (s/optional-key :storage-tier) storage-tier})

(def max-multipart-count 10000)
(def part-num (s/constrained s/Int (between? 1 max-multipart-count)))

(s/defschema CommitMultipartUploadPart
  {:etag s/Str
   :part-num part-num})

(s/defschema CommitMultipartUpload
  {:parts-to-commit [CommitMultipartUploadPart]
   (s/optional-key :parts-to-exclude) [s/Int]})

(def multipart-upload-route
  {:path-parts (into bucket-path ["/u/" :object-name])
   :path-schema object-path-schema
   :query-schema {:uploadId s/Str}})

(def override-req-opts
  "Overrides request options for `get-object` calls."
  {:name ::override-req-opts
   :enter #(update % :request assoc :as :stream)})

(def fix-get-namespace-content-type
  "When invoking `get-namespace`, it returns `Content-Type: application/json` but the body
   is actually plain text.  This interceptor overwrites the response content type to fix this."
  {:name ::fix-content-type
   :leave (fn [ctx]
            (assoc-in ctx [:response :headers "content-type"] "text/plain"))})

(def re-add-metadata
  "Body coercion drops metadata map for some reason (maybe because it's a string/string map?), 
   so we re-add it here.  This is really hacky but I'm really tired of trying to figure out
   Martian's weirdnesses..."
  {:name ::re-add-metadata
   :enter (fn [ctx]
            (let [md (get-in ctx [:params :multipart :metadata])]
              (cond-> ctx
                ;; Add metadata and re-convert keys to strings
                md (assoc-in [:request :body :metadata] (mc/map-keys name md)))))})

(def routes
  [{:route-name :get-namespace
    :method :get
    :path-parts ["/n"]
    :interceptors [fix-get-namespace-content-type]}
   
   (p/paged-route
    {:route-name :list-buckets
     :method :get
     :path-parts ["/n/" :ns "/b"]
     :path-schema {:ns s/Str}
     :query-schema {:compartment-id s/Str}
     :produces json})
   
   {:route-name :get-bucket
    :method :get
    :path-parts bucket-path
    :path-schema bucket-path-schema
    :produces json}

   (p/paged-route
    {:route-name :list-objects
     :method :get
     :path-parts (conj bucket-path "/o")
     :path-schema bucket-path-schema
     :query-schema ListObjectsQuery
     :produces json})

   {:route-name :put-object
    :method :put
    :path-parts object-path
    :path-schema object-path-schema
    :body-schema {:contents s/Any}
    :header-schema PutObjectHeaders}

   {:route-name :get-object
    :method :get
    :path-parts object-path
    :path-schema object-path-schema
    :interceptors [override-req-opts]
    :produces ["application/octet-stream"]}
   
   {:route-name :delete-object
    :method :delete
    :path-parts object-path
    :path-schema object-path-schema}

   {:route-name :head-object
    :method :head
    :path-parts object-path
    :path-schema object-path-schema}

   {:route-name :rename-object
    :method :post
    :path-parts (conj bucket-path "/actions/renameObject")
    :path-schema bucket-path-schema
    :body-schema {:rename RenameObject}
    :consumes json
    :produces json}

   {:route-name :copy-object
    :method :post
    :path-parts (conj bucket-path "/actions/copyObject")
    :path-schema bucket-path-schema
    :body-schema {:copy CopyObject}
    :consumes json
    :produces json}

   {:route-name :create-multipart-upload
    :method :post
    :path-parts (conj bucket-path "/u")
    :path-schema bucket-path-schema
    :body-schema {:multipart CreateMultipartUpload}
    :consumes json
    :produces json
    :interceptors [re-add-metadata]}

   (p/paged-route
    {:route-name :list-multipart-uploads
     :method :get
     :path-parts (conj bucket-path "/u")
     :path-schema bucket-path-schema
     :produces json})
   
   (-> multipart-upload-route
       (assoc :route-name :upload-part
              :method :put
              :body-schema {:part s/Any}
              :header-schema {(s/optional-key :content-type) s/Str})
       (assoc-in [:query-schema :uploadPartNum] part-num))

   (-> multipart-upload-route
       (assoc :route-name :commit-multipart-upload
              :method :post
              :body-schema {:multipart CommitMultipartUpload}
              :consumes json))

   (-> multipart-upload-route
       (assoc :route-name :abort-multipart-upload
              :method :delete))

   (-> multipart-upload-route
       (assoc :route-name :list-multipart-upload-parts
              :method :get)
       (p/paged-route))])

(def host (comp (partial format "https://objectstorage.%s.oraclecloud.com") :region))

(def custom-encoders
  {"application/octet-stream" {:as :stream
                               :encode identity
                               :decode identity}})

(def coerce-response
  (-> (aleph/make-encoders csk/->kebab-case-keyword)
      (merge custom-encoders)
      (mi/coerce-response)))

(def keywordize-headers
  {:name ::keywordize-headers
   :leave (fn [ctx]
            (mc/update-existing-in ctx [:response :headers] (partial mc/map-keys csk/->kebab-case-keyword)))})

(defn make-context
  "Creates Martian context for the given configuration.  This context
   should be passed to subsequent requests."
  [conf]
  (letfn [(exclude? [{:keys [handler]}]
            (and (= :put (:method handler))
                 (contains? #{:put-object :upload-part} (:route-name handler))))]
    (martian/bootstrap
     (host conf)
     routes
     ;; Replace httpkit with aleph because httpkit is unable to stream large responses without
     ;; completely buffering them.  This doesn't work well with get-object.
     {:interceptors (-> (cm/default-interceptors (assoc conf :exclude-body? exclude?))
                        (mi/inject aleph/perform-request :replace :martian.httpkit/perform-request)
                        (mi/inject coerce-response :replace ::mi/coerce-response)
                        (mi/inject keywordize-headers :before ::mi/coerce-response))})))

(def send-request martian/response-for)

(u/define-endpoints *ns* routes martian/response-for)

(ns monkey.oci.os.core
  (:require [camel-snake-kebab.core :as csk]
            [clojure.data.json :as json]
            [clojure.string :as cs]
            [manifold.deferred :as md]
            [martian
             [core :as martian]
             [httpkit :as martian-http]]
            [monkey.oci.sign :as sign]
            [schema.core :as s]))

(set! *warn-on-reflection* true)

(defn- host [{:keys [region]}]
  (format "https://objectstorage.%s.oraclecloud.com" region))

(defn- parse-body [b]
  (json/read-str b :key-fn csk/->kebab-case-keyword))

(defn- url-with-query
  "Builds the full url, including query params"
  [{:keys [url query-params]}]
  (letfn [(->str [qp]
            (->> qp
                 (map (fn [[k v]]
                        ;; TODO Url escaping
                        (str (name k) "=" v)))
                 (cs/join "&")))]
    (cond-> url
      (not-empty query-params) (str "?" (->str query-params)))))

(defn- sign-request
  "Adds authorization signature to the Martian request"
  [conf {:keys [request] :as ctx}]
  (let [sign-headers (-> request
                         (assoc :url (url-with-query request))
                         (sign/sign-headers))
        headers (sign/sign conf sign-headers)]
    (update-in ctx [:request :headers] merge headers)))

(defn signer [conf]
  {:name ::sign-request
   :enter (partial sign-request conf)})

(def body-parser
  {:name ::parse-body
   :leave (fn [ctx]
            (update ctx :response (comp parse-body :body)))})

(def routes
  [{:route-name :get-namespace
    :method :get
    :path-parts ["/n"]}
   
   {:route-name :list-buckets
    :method :get
    :path-parts ["/n/" :ns "/b"]
    :path-schema {:ns s/Str}
    :query-schema {:compartmentId s/Str}}
   
   {:route-name :get-bucket
    :method :get
    :path-parts ["/n/" :ns "/b/" :bucketName]
    :path-schema {:ns s/Str :bucketName s/Str}}

   {:route-name :list-objects
    :method :get
    :path-parts ["/n/" :ns "/b/" :bucketName "/o"]
    :path-schema {:ns s/Str :bucketName s/Str}}])

(defn make-context
  "Creates Martian context for the given configuration.  This context
   should be passed to subsequent requests."
  [conf]
  (martian/bootstrap
   (host conf)
   routes
   {:interceptors (concat [body-parser]
                          martian/default-interceptors
                          [(signer conf)
                           martian-http/perform-request])}))

(defn- make-request-fn
  "Creates a request function for the given request id.  The
   function takes the context (created using `make-context`) and
   any parameters.  Which parameters are accepted depend on the
   route definition."
  [id]
  (fn [ctx & [params]]
    (martian/response-for ctx id params)))

(def get-namespace (make-request-fn :get-namespace))

(def list-buckets (make-request-fn :list-buckets))
(def get-bucket (make-request-fn :get-bucket))

(def list-objects (make-request-fn :list-objects))

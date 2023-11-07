(ns monkey.oci.os.core
  "Higher level core functionality.  These mostly delegate to the `martian` ns,
   which provides the actual HTTP invocation functions."
  (:require [manifold.deferred :as md]
            [monkey.oci.os.martian :as m]
            [monkey.oci.common.utils :as u]))

(def make-client m/make-context)

(defn- throw-on-error [{:keys [status] :as resp}]
  (if (>= status 400)
    (throw (ex-info (str "Received error reponse from server, status " status) resp))
    resp))

(defn invoke-endpoint
  "Invokes the given endpoint using the client by sending a request to
   the configured Martian route.  If the response has a success status,
   it returns the body.  Otherwise an exception is thrown.  To emphasize
   the remote nature of the request, and also because Httpkit works async,
   this returns a Manifold `deferred`."
  [client ep params]
  (md/chain
   (m/send-request client ep params)
   throw-on-error
   :body))

;; Declare functions for each of the endpoints
(u/define-endpoints *ns* m/routes invoke-endpoint)

;; Overwrite head-object to return a boolean according to status
(defn head-object [client args]
  (md/chain
   (m/head-object client args)
   :status
   (partial = 200)))

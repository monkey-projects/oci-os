(ns monkey.oci.os.core
  "Higher level core functionality.  These mostly delegate to the `martian` ns,
   which provides the actual HTTP invocation functions."
  (:require [monkey.oci.os.martian :as m]))

(def make-client m/make-context)

(defn invoke-endpoint
  "Invokes the given endpoint using the client by sending a request to
   the configured Martian route."
  [client ep & [params]]
  (m/send-request client ep params))

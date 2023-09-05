(ns monkey.oci.os.signing
  "Functions for request signing.  These are then used by Martian
   as an interceptor."
  (:require [clojure.string :as cs]
            [monkey.oci.sign :as sign]))

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
  [conf {:keys [request handler] :as ctx}]
  (let [sign-headers (cond->
                         (-> request
                             (assoc :url (url-with-query request))
                             (sign/sign-headers))
                       ;; Special treatment for some put operations
                       (and (= :put (:method handler))
                            (= :put-object (:route-name handler)))
                       (select-keys ["date" "(request-target)" "host"]))
        headers (sign/sign conf sign-headers)]
    (update-in ctx [:request :headers] sign/merge-headers headers)))

(defn signer
  "Creates a Martian interceptor that signs each request using the
   given authentication configuration."
  [conf]
  {:name ::sign-request
   :enter (partial sign-request conf)})

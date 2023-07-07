(ns monkey.oci.os.utils
  (:require [buddy.core.keys.pem :as pem]
            [clojure.java.io :as io])
  (:import java.util.Base64
           java.io.StringReader))

(defn base64-decode
  "Decodes given base64 string back into a string"
  [s]
  (-> (Base64/getDecoder)
      (.decode s)
      (String.)))

(defn- ->reader
  "If `s` points to an existing file, open it as a file reader,
   otherwise returns a string reader, assuming the contents is
   base64 encoded."
  [s]
  (if (.exists (io/file s))
    (io/reader s)
    ;; Read it, decode it, and put it back in a reader
    (StringReader. (base64-decode s))))

(defn load-privkey [src]
  (with-open [r (->reader src)]
    (pem/read-privkey r nil)))

(ns monkey.oci.os.utils
  (:require [buddy.core.keys.pem :as pem]
            [clojure.java.io :as io])
  (:import java.util.Base64
           java.io.StringReader))

(set! *warn-on-reflection* true)

(defn base64-decode
  "Decodes given base64 string back into a string"
  [^String s]
  (-> (Base64/getDecoder)
      (.decode s)
      (String.)))

(defn- ^java.io.Reader ->reader
  "If `s` points to an existing file, open it as a file reader,
   otherwise returns a string reader, assuming the contents is
   base64 encoded."
  [s]
  (if (.exists (io/file s))
    (io/reader s)
    ;; Read it, decode it, and put it back in a reader
    (StringReader. (base64-decode s))))

(defn load-privkey
  "Loads private key from either source file, or string.  If `src` points
   to an existing file, it's loaded from that file.  If it's a string, it
   must be base64 encoded."
  [^String src]
  (with-open [r (->reader src)]
    (pem/read-privkey r nil)))

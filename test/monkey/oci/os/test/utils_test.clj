(ns monkey.oci.os.test.utils-test
  (:require [clojure.test :refer :all]
            [monkey.oci.os.utils :as sut]))

(def test-file "test/testkey")

(defn- base64-encode [s]
  (-> (java.util.Base64/getEncoder)
      (.encodeToString (.getBytes s))))

(deftest load-privkey
  (testing "loads from a file"
    (is (some? (sut/load-privkey (str "dev-resources/" test-file)))))

  (testing "loads from string"
    (is (some? (-> test-file
                   (clojure.java.io/resource)
                   (slurp)
                   (base64-encode)
                   (sut/load-privkey))))))

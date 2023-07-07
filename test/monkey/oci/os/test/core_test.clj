(ns monkey.oci.os.test.core-test
  (:require [clojure.test :refer :all]
            [monkey.oci.os.core :as sut]
            [org.httpkit.fake :as f]
            [org.httpkit.client :as http])
  (:import java.security.KeyPairGenerator))

(defn generate-key []
  (-> (doto (KeyPairGenerator/getInstance "RSA")
        (.initialize 2048))
      (.generateKeyPair)
      (.getPrivate)))

(defonce test-config {:user-ocid "test-user"
                      :tenancy-ocid "test-tenancy"
                      :private-key (generate-key)
                      :key-fingerprint "test-fingerprint"
                      :region "test-region"})

(deftest get-namespace
  (testing "sends GET request to backend"
    (f/with-fake-http
      ["https://objectstorage.test-region.oraclecloud.com/n" {:body "\"test-ns\""}]
      
      (is (= "test-ns" @(sut/get-namespace test-config))))))

(deftest list-buckets
  (testing "sends GET request to backend"
    (f/with-fake-http
      ["https://objectstorage.test-region.oraclecloud.com/n/test-ns/b?compartmentId=test-cid" {:body "{}"}]
      
      (is (= {} @(sut/list-buckets test-config "test-ns" "test-cid"))))))

(ns monkey.oci.os.test.core-test
  (:require [clojure.test :refer :all]
            [martian.test :as mt]
            [monkey.oci.os.core :as sut]
            [org.httpkit.fake :as f]
            [org.httpkit.client :as http])
  (:import java.security.KeyPairGenerator))

(defn generate-key []
  (-> (doto (KeyPairGenerator/getInstance "RSA")
        (.initialize 2048))
      (.generateKeyPair)
      (.getPrivate)))

(def test-ctx (sut/make-context
               {:user-ocid "test-user"
                :tenancy-ocid "test-tenancy"
                :private-key (generate-key)
                :key-fingerprint "test-fingerprint"
                :region "test-region"}))

(deftest get-namespace
  (testing "sends GET request to backend"
    (f/with-fake-http
      ["https://objectstorage.test-region.oraclecloud.com/n" {:body "\"test-ns\""}]
      
      (is (= "test-ns" @(sut/get-namespace test-ctx))))))

(deftest list-buckets
  (testing "sends GET request to backend"
    (f/with-fake-http
      ["https://objectstorage.test-region.oraclecloud.com/n/test-ns/b"
       {:body "{\"name\":\"test bucket\"}"}]
      
      (is (= {:name "test bucket"} @(sut/list-buckets test-ctx {:ns "test-ns"
                                                                :compartment-id "test-cid"}))))))

(deftest get-bucket
  (testing "invokes `:get-bucket` request"
    (let [ctx (mt/respond-with-constant test-ctx {:get-bucket {:body "{}"}})]
      (is (map? @(sut/get-bucket ctx {:ns "test-ns" :bucket-name "test-bucket"})))))

  (testing "fails when no bucket name given"
    (let [ctx (mt/respond-with-constant test-ctx {:get-bucket {:body "{}"}})]
      (is (thrown? Exception @(sut/get-bucket ctx {:ns "test-ns"}))))))

(deftest list-objects
  (testing "invokes `:list-objects` request"
    (let [ctx (mt/respond-with-constant test-ctx {:list-objects {:body "{}"}})]
      (is (map? @(sut/list-objects ctx {:ns "test-ns" :bucket-name "test-bucket"}))))))

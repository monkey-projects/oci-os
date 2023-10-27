(ns monkey.oci.os.test.core-test
  (:require [clojure.test :refer :all]
            [manifold.deferred :as md]
            [monkey.oci.os
             [core :as sut]
             [martian :as martian]]))

(deftest make-client
  (testing "creates client object"
    (is (some? (sut/make-client {:private-key "testkey"})))))

(deftest invoke-endpoint
  (let [client (sut/make-client {:private-key (reify java.security.PrivateKey)
                                 :user-ocid "testuser"
                                 :tenancy-ocid "test-tenancy"
                                 :key-fingerprint "test-fingerprint"})]

    (testing "returns body async on 2xx response"
      (with-redefs [martian/send-request (constantly
                                          (future {:status 200
                                                   :body "ok"}))]
        (is (= "ok" @(sut/invoke-endpoint client :get-namespace {})))))

    (testing "throws exception on 4xx status"
      (with-redefs [martian/send-request (constantly
                                          (future {:status 404
                                                   :body "not found"}))]
        (is (thrown? Exception @(sut/invoke-endpoint client :get-namespace {})))))))

(deftest get-namespace
  (testing "function exists"
    (is (fn? sut/get-namespace))))

(deftest head-object
  (testing "true if request returns 200"
    (with-redefs [martian/head-object (constantly (future {:status 200}))]
      (is (true? @(sut/head-object {} {})))))

  (testing "false if request returns 404"
    (with-redefs [martian/head-object (constantly (future {:status 404}))]
      (is (false? @(sut/head-object {} {}))))))

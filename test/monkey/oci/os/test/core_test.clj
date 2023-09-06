(ns monkey.oci.os.test.core-test
  (:require [clojure.test :refer :all]
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

    (with-redefs [martian/send-request (constantly "ok")]
      
      (testing "sends request using martian"
        (is (some? (sut/invoke-endpoint client :get-namespace)))))))

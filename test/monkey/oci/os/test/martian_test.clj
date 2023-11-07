(ns monkey.oci.os.test.martian-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [martian.test :as mt]
            [monkey.oci.common.utils :refer [generate-key]]
            [monkey.oci.os.martian :as sut]
            [monkey.oci.sign :as sign]
            [org.httpkit.fake :as f]
            [org.httpkit.client :as http]))

(def test-config {:user-ocid "test-user"
                  :tenancy-ocid "test-tenancy"
                  :private-key (generate-key)
                  :key-fingerprint "test-fingerprint"
                  :region "test-region"})

(def test-ctx (sut/make-context test-config))

(deftest get-namespace
  (testing "sends GET request to backend"
    (f/with-fake-http
      ["https://objectstorage.test-region.oraclecloud.com/n" {:body "\"test-ns\""
                                                              :headers {:content-type "application/json"}}]
      
      (is (= "test-ns" (:body @(sut/get-namespace test-ctx)))))))

(deftest list-buckets
  (testing "sends GET request to backend"
    (f/with-fake-http
      ["https://objectstorage.test-region.oraclecloud.com/n/test-ns/b"
       {:body "{\"name\":\"test bucket\"}"
        :headers {:content-type "application/json"}}]
      
      (is (= {:name "test bucket"} (-> (sut/list-buckets test-ctx {:ns "test-ns"
                                                                   :compartment-id "test-cid"})
                                       (deref)
                                       :body))))))

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

(deftest put-object
  (testing "invokes `:put-object` endpoint"
    (let [ctx (mt/respond-with-constant test-ctx {:put-object {:body "{}"}})]
      (is (map? @(sut/put-object ctx {:ns "test-ns" :bucket-name "test-bucket" :object-name "test.txt"
                                      :contents "test"})))))

  (testing "doesn't include body in signature headers"
    (let [ctx (mt/respond-with test-ctx {:put-object (fn [req]
                                                       (get-in req [:headers "authorization"]))})]
      (is (= (get (sign/sign test-config
                             (-> (sign/sign-headers
                                  {:url "https://objectstorage.test-region.oraclecloud.com/n/test-ns/b/test-bucket/o/test.txt"
                                   :method :put})
                                 (select-keys ["date" "(request-target)" "host"])))
                  "authorization")
             @(sut/put-object ctx {:ns "test-ns" :bucket-name "test-bucket" :object-name "test.txt"
                                   :contents "test"})))))

  (testing "passes body as-is"
    (let [ctx (mt/respond-with test-ctx {:put-object (fn [req]
                                                       (:body req))})]
      (is (= "test" @(sut/put-object ctx {:ns "test-ns"
                                          :bucket-name "test-bucket"
                                          :object-name "test.txt"
                                          :contents "test"}))))))

(deftest get-object
  (testing "invokes `:get-object` request"
    (let [ctx (mt/respond-with-constant test-ctx {:get-object {:body "test"}})]
      (is (map? @(sut/get-object ctx {:ns "test-ns" :bucket-name "test-bucket" :object-name "test-obj"}))))))

(deftest delete-object
  (testing "invokes `:delete-object` request"
    (let [ctx (mt/respond-with-constant test-ctx {:delete-object {:body "test"}})]
      (is (map? @(sut/delete-object ctx {:ns "test-ns" :bucket-name "test-bucket" :object-name "test-obj"}))))))

(deftest head-object
  (testing "invokes `:head-object` request"
    (let [ctx (mt/respond-with-constant test-ctx {:head-object {:body "test"}})]
      (is (map? @(sut/head-object ctx {:ns "test-ns" :bucket-name "test-bucket" :object-name "test-obj"}))))))

(deftest rename-object
  (testing "invokes `:rename-object` request"
    (let [ctx (mt/respond-with-constant test-ctx {:rename-object {:body "test"}})]
      (is (map? @(sut/rename-object ctx {:ns "test-ns" :bucket-name "test-bucket"
                                         :rename {:source-name "old.txt"
                                                  :new-name "new.txt"}})))))

  (testing "sends authorization header"
    (f/with-fake-http [(constantly true) (fn [f req c]
                                           (c {:body (:headers req)}))]
      (let [resp (-> test-ctx
                     (sut/rename-object {:ns "test-ns" :bucket-name "test-bucket"
                                         :rename {:source-name "old.txt"
                                                  :new-name "new.txt"}})
                     (deref)
                     :body)]
        (is (string? (get resp "authorization")))
        (is (string? (get resp "content-length")))
        (is (= "application/json" (get resp "content-type")))))))

(deftest copy-object
  (testing "invokes `:copy-object` request"
    (let [ctx (mt/respond-with-constant test-ctx {:copy-object {:body "test"}})]
      (is (map? @(sut/copy-object ctx {:ns "test-ns" :bucket-name "test-bucket"
                                       :copy {:source-object-name "old.txt"
                                              :destination-object-name "new.txt"
                                              :destination-namespace "test-ns"
                                              :destination-bucket "test-bucket"
                                              :destination-region "test-region"}}))))))

(deftest multipart-uploads
  (testing "can create multipart upload"
    (is (= 200 (-> test-ctx
                   (mt/respond-with-constant {:create-multipart-upload
                                              {:status 200
                                               :body {:bucket "test-bucket"
                                                      :upload-id "test-id"}}})
                   (sut/create-multipart-upload
                    {:ns "test-ns"
                     :bucket-name "test-bucket"
                     :multipart {:object "test.txt"}})
                   deref
                   :status))))

  (testing "can upload part"
    (is (= 200 (-> test-ctx
                   (mt/respond-with-constant {:upload-part
                                              {:status 200}})
                   (sut/upload-part
                    {:ns "test-ns"
                     :bucket-name "test-bucket"
                     :object-name "test-obj"
                     :upload-id "test-id"
                     :upload-part-num 1
                     :part "part contents"})
                   deref
                   :status))))

  (testing "can commit upload"
    (is (= 200 (-> test-ctx
                   (mt/respond-with-constant {:commit-multipart-upload
                                              {:status 200}})
                   (sut/commit-multipart-upload
                    {:ns "test-ns"
                     :bucket-name "test-bucket"
                     :object-name "test-obj"
                     :upload-id "test-id"
                     :multipart {:parts-to-commit [{:etag "test-tag"
                                                    :part-num 1}]}})
                   deref
                   :status))))

  (testing "can abort upload"
    (is (= 204 (-> test-ctx
                   (mt/respond-with-constant {:abort-multipart-upload
                                              {:status 204}})
                   (sut/abort-multipart-upload
                    {:ns "test-ns"
                     :bucket-name "test-bucket"
                     :object-name "test-obj"
                     :upload-id "test-id"})
                   deref
                   :status))))

  (testing "can list multipart uploads"
    (is (= 200 (-> test-ctx
                   (mt/respond-with-constant {:list-multipart-uploads
                                              {:status 200
                                               :body {:items []}}})
                   (sut/list-multipart-uploads
                    {:ns "test-ns"
                     :bucket-name "test-bucket"})
                   deref
                   :status))))

  (testing "can list upload parts"
    (is (= 200 (-> test-ctx
                   (mt/respond-with-constant {:list-multipart-upload-parts
                                              {:status 200
                                               :body {:items []}}})
                   (sut/list-multipart-upload-parts
                    {:ns "test-ns"
                     :bucket-name "test-bucket"
                     :object-name "test-obj"
                     :upload-id "test-upload"})
                   deref
                   :status)))))

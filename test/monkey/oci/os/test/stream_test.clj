(ns monkey.oci.os.test.stream-test
  (:require [clojure.test :refer [deftest testing is]]
            [manifold
             [deferred :as md]
             [stream :as ms]]
            [martian
             [core :as martian]
             [test :as mt]]
            [monkey.oci.common.utils :as u]
            [monkey.oci.os
             [martian :as m]
             [stream :as sut]])
  (:import [java.io PipedInputStream PipedOutputStream]))

(def test-ctx (-> {:user-ocid "test-user"
                   :tenancy-ocid "test-tenancy"
                   :private-key (u/generate-key)
                   :key-fingerprint "test-fingerprint"
                   :region "test-region"}
                  (m/make-context)))

(defn- wait-for [f timeout timeout-val]
  (let [now (fn [] (System/currentTimeMillis))
        s (now)]
    (loop [t s]
      (if-let [r (f)]
        r
        (if (> (- t s) timeout)
          timeout-val
          (do
            (Thread/sleep 100)
            (recur (now))))))))

(deftest stream->multipart
  (testing "creates multipart upload"
    (let [inv (atom nil)]
      (is (ms/stream? (-> test-ctx
                          (mt/respond-with {:create-multipart-upload (fn [req]
                                                                       (reset! inv req)
                                                                       {:status 200})})
                          (sut/stream->multipart {:ns "test-ns"
                                                  :bucket-name "test-bucket"
                                                  :object-name "test-file"})
                          (deref))))
      (is (some? @inv))))

  (testing "uploads part when putting to stream"
    (let [id (str (random-uuid))
          uploaded (atom [])
          ctx (-> test-ctx
                  (mt/respond-with
                   {:create-multipart-upload
                    (fn [{:keys [params body] :as req}]
                      {:status 200
                       :body {:upload-id id
                              :bucket "test-bucket"
                              :namespace "test-ns"
                              :object (:object body)}})
                    :upload-part
                    (fn [{:keys [body]}]
                      (swap! uploaded conj body)
                      {:status 200})}))
          s @(sut/stream->multipart ctx {:bucket-name "test-bucket"
                                         :ns "test-ns"
                                         :object-name "test-obj"})
          put-part #(deref (ms/put! s (str "part " %)) 500 :timeout)]
      (is (not= :timeout (put-part 1)))
      (is (not= :timeout (put-part 2)))
      (is (= 2 (count @uploaded)))
      (is (= ["part 1" "part 2"] @uploaded))))

  (testing "commits multipart when stream closed"
    (let [committed (atom nil)
          ctx (mt/respond-with
               test-ctx
               {:create-multipart-upload
                (constantly
                 {:status 200
                  :body {:upload-id "test-id"
                         :bucket "test-bucket"
                         :object "test-obj"
                         :namespace "test-ns"}})
                :upload-part
                (constantly
                 {:status 200
                  :headers {:etag "test-etag"}})
                :commit-multipart-upload
                (fn [req]
                  (reset! committed req)
                  {:status 200})})
          s @(sut/stream->multipart ctx {:bucket-name "test-bucket"
                                         :ns "test-ns"
                                         :object-name "test-obj"})]
      (is (not= :timeout (-> (ms/put! s "test part")
                             (deref 500 :timeout))))
      (is (nil? (ms/close! s)))
      (is (not= :timeout (wait-for #(pos? (count @committed)) 1000 :timeout)))))

  (testing "aborts on upload error"
    (let [aborted (atom nil)
          ctx (mt/respond-with
               test-ctx
               {:create-multipart-upload
                (constantly
                 {:status 200
                  :body {:upload-id "test-id"
                         :bucket "test-bucket"
                         :object "test-obj"
                         :namespace "test-ns"}})
                :upload-part
                (fn [_]
                  {:status 400 :body {:message "some error occurred"}})
                :abort-multipart-upload
                (fn [req]
                  (reset! aborted req)
                  {:status 200})})
          s @(sut/stream->multipart ctx {:bucket-name "test-bucket"
                                         :ns "test-ns"
                                         :object-name "test-obj"})]
      (is (not= :timeout (-> (ms/put! s "test part")
                             (deref 500 :timeout))))
      (is (not= :timeout (wait-for #(pos? (count @aborted)) 1000 :timeout)))))

  (testing "aborts on upload exception")

  (testing "aborts when cancel token is put on stream")

  (testing "creates a new upload when max parts has been reached"))

(deftest input-stream->multipart
  (testing "creates multipart and uploads parts until stream closes"
    (let [in (PipedInputStream.)
          os (PipedOutputStream. in)
          ctx (-> test-ctx
                  (mt/respond-with
                   {:create-multipart-upload
                    (constantly
                     {:status 200
                      :body {:upload-id "test-id"
                             :bucket "test-bucket"
                             :object "test-obj"
                             :namespace "test-ns"}})
                    :upload-part
                    (constantly
                     {:status 200
                      :headers {:etag "test-etag"}})
                    :commit-multipart-upload
                    (constantly
                     {:status 200
                      :body :committed})
                    :abort-multipart-upload
                    (constantly
                     {:status 500
                      :body :aborted})}))
          p (sut/input-stream->multipart
             ctx
             {:ns "test-ns"
              :bucket-name "test-bucket"
              :object-name "test-file"
              :input-stream in})
          s "test string"]
      (is (md/deferred? p) "returns a deferred")
      (is (nil? (.write os (.getBytes s))))
      (is (nil? (.close os)))
      (is (nil? (.close in)))
      (is (= {:status 200
              :body :committed}
             (deref p 1000 :timeout)))))

  (testing "aborts empty streams"
    (let [in (PipedInputStream.)
          os (PipedOutputStream. in)
          ctx (-> test-ctx
                  (mt/respond-with
                   {:create-multipart-upload
                    (constantly
                     {:status 200
                      :body {:upload-id "test-id"
                             :bucket "test-bucket"
                             :object "test-obj"
                             :namespace "test-ns"}})
                    :upload-part
                    (constantly
                     {:status 200
                      :headers {:etag "test-etag"}})
                    :abort-multipart-upload
                    (fn [req]
                      {:status 200
                       :body :aborted})}))
          p (sut/input-stream->multipart
             ctx
             {:ns "test-ns"
              :bucket-name "test-bucket"
              :object-name "test-file"
              :input-stream in})]
      (is (md/deferred? p) "returns a deferred")
      ;; Close without sending anything
      (is (nil? (.close os)))
      (is (nil? (.close in)))
      (is (= {:status 200
              :body :aborted}
             (deref p 1000 :timeout))))))

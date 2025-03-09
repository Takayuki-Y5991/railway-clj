(ns railway-clj.retry-test
  (:require
   [railway-clj.retry :refer [retry
                              retryable-error?]]
   [railway-clj.core :refer [failure success]]
   [clojure.test :refer [deftest is testing]]))

(deftest retryable-error?-test
  (testing "nil status should be retryable"
    (is (retryable-error? {:details {}})))

  (testing "status 408 should be retryable"
    (is (retryable-error? {:details {:status 408}})))

  (testing "status 429 should be retryable"
    (is (retryable-error? {:details {:status 429}})))

  (testing "status 500 should be retryable"
    (is (retryable-error? {:details {:status 500}})))

  (testing "status 502 should be retryable"
    (is (retryable-error? {:details {:status 502}})))

  (testing "status 503 should be retryable"
    (is (retryable-error? {:details {:status 503}})))

  (testing "status 504 should be retryable"
    (is (retryable-error? {:details {:status 504}})))

  (testing "status 400 should not be retryable"
    (is (not (retryable-error? {:details {:status 400}}))))

  (testing "status 404 should not be retryable"
    (is (not (retryable-error? {:details {:status 404}})))))

(deftest retry-with-backoff-test
  (testing "Should use custom backoff factor"
    (let [call-count (atom 0)
          start-time (System/currentTimeMillis)
          test-fn (fn [_]
                    (swap! call-count inc)
                    (if (< @call-count 3)
                      (failure {:error "Temporary error"})
                      (success "success")))
          ;; 実際に短い時間待機する
          retried-fn (retry test-fn {:max-attempts 5
                                     :backoff-ms 5
                                     :backoff-factor 2
                                     :jitter 0})]

      (is (= (success "success") (retried-fn "test")))
      (is (= 3 @call-count) "Function should be called exactly 3 times")
      (let [elapsed (- (System/currentTimeMillis) start-time)]
        ;; 少なくとも期待される待機時間（5ms + 10ms = 15ms）経過していることを確認
        (is (>= elapsed 15) "Should have waited for backoff periods")))))

(deftest retry-with-arguments-test
  (testing "Should pass arguments correctly to the function"
    (let [received-args (atom nil)
          test-fn (fn [& args]
                    (reset! received-args args)
                    (success (first args)))
          retried-fn (retry test-fn {:backoff-ms 1})]

      (is (= (success "arg1") (retried-fn "arg1" "arg2")))
      (is (= ["arg1" "arg2"] @received-args) "Arguments should be passed correctly"))))
(ns railway-clj.retry-test
  (:require
   [railway-clj.retry :refer [retry
                              retryable-error?]]
   [railway-clj.core :refer [failure failure? success success?]]
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

(deftest retry-with-jitter-test
  (testing "Should apply jitter to backoff timing"
    (let [call-count (atom 0)
          start-times (atom [])
          test-fn (fn [_]
                    (let [current-time (System/currentTimeMillis)]
                      (swap! start-times conj current-time)
                      (swap! call-count inc)
                      (if (< @call-count 3)
                        (failure {:error "Temporary error"})
                        (success "success"))))
          retried-fn (retry test-fn {:max-attempts 5
                                     :backoff-ms 100
                                     :backoff-factor 1
                                     :jitter 0.5})] ; 50% jitter
      
      (is (= (success "success") (retried-fn "test")))
      (is (= 3 @call-count) "Function should be called exactly 3 times")
      
      ;; Check that there were delays between calls
      (let [times @start-times
            delay1 (- (nth times 1) (nth times 0))
            delay2 (- (nth times 2) (nth times 1))]
        ;; With 50% jitter, delays should be in range [50ms, 150ms]
        (is (>= delay1 50) "First delay should be at least 50ms")
        (is (<= delay1 150) "First delay should be at most 150ms")
        (is (>= delay2 50) "Second delay should be at least 50ms")
        (is (<= delay2 150) "Second delay should be at most 150ms")))))

(deftest retry-with-custom-retryable-predicate-test
  (testing "Should use custom retryable predicate"
    (let [call-count (atom 0)
          test-fn (fn [error-type]
                    (swap! call-count inc)
                    (case error-type
                      :retryable (failure {:error "Retryable error" :details {:status 500}})
                      :non-retryable (failure {:error "Non-retryable error" :details {:status 400}})
                      :success (success "success")))
          ;; Custom predicate: only retry 5xx errors
          custom-retryable? (fn [error]
                              (let [status (get-in error [:details :status])]
                                (and status (>= status 500) (< status 600))))
          retried-fn (retry test-fn {:max-attempts 3
                                     :backoff-ms 1
                                     :retryable? custom-retryable?})]
      
      ;; Test with retryable error
      (reset! call-count 0)
      (let [result (retried-fn :retryable)]
        (is (failure? result))
        (is (= "Retryable error" (get-in result [:error :error])))
        (is (= 3 @call-count) "Should retry retryable errors up to max attempts"))
      
      ;; Test with non-retryable error
      (reset! call-count 0)
      (let [result (retried-fn :non-retryable)]
        (is (failure? result))
        (is (= "Non-retryable error" (get-in result [:error :error])))
        (is (= 1 @call-count) "Should not retry non-retryable errors"))
      
      ;; Test with success
      (reset! call-count 0)
      (let [result (retried-fn :success)]
        (is (success? result))
        (is (= "success" (:value result)))
        (is (= 1 @call-count) "Should not retry successful calls"))))

(deftest retry-max-attempts-boundary-test
  (testing "Should respect exact max attempts limit"
    (let [call-count (atom 0)
          test-fn (fn [_]
                    (swap! call-count inc)
                    (failure {:error "Always fails"}))
          retried-fn (retry test-fn {:max-attempts 1
                                     :backoff-ms 1})]
      
      ;; With max-attempts 1, should not retry at all
      (let [result (retried-fn "test")]
        (is (failure? result))
        (is (= 1 @call-count) "Should call exactly once with max-attempts 1"))
      
      ;; Test with max-attempts 2
      (reset! call-count 0)
      (let [retried-fn-2 (retry test-fn {:max-attempts 2
                                         :backoff-ms 1})
            result (retried-fn-2 "test")]
        (is (failure? result))
        (is (= 2 @call-count) "Should call exactly twice with max-attempts 2"))))))
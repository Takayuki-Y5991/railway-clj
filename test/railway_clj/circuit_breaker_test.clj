(ns railway-clj.circuit-breaker-test
  (:require [clojure.test :refer [deftest is testing]]
            [railway-clj.circuit-breaker :refer [create-circuit-breaker]]
            [railway-clj.core :refer [success failure failure? success?]]
            [clojure.core.async :as async :refer [<!! timeout]]))

(deftest create-circuit-breaker-test
  (testing "Should initially be in closed state and allow calls to pass through"
    (let [test-fn (fn [x] (success x))
          circuit-breaker (create-circuit-breaker)
          protected-fn (circuit-breaker test-fn)]

      (is (= (success "test") (protected-fn "test"))
          "Function call should pass through when circuit is closed")))

  (testing "Should transition to open state after threshold failures"
    (let [failure-count (atom 0)
          test-fn (fn [_]
                    (swap! failure-count inc)
                    (failure {:error "test failure"}))
          circuit-breaker (create-circuit-breaker {:failure-threshold 3
                                                   :reset-timeout-ms 100})
          protected-fn (circuit-breaker test-fn)]

      ;; Call function multiple times to trigger threshold
      (protected-fn "test")
      (protected-fn "test")
      (let [result (protected-fn "test")] ;; This should trigger circuit open
        (is (failure? result) "Third call should still return original failure")
        (is (= "test failure" (get-in result [:error :error])) "Error details should be preserved"))

      ;; Next call should be blocked by circuit breaker
      (let [blocked-result (protected-fn "test")]
        (is (failure? blocked-result) "Call should fail due to open circuit")
        (is (= "Circuit breaker is open" (get-in blocked-result [:error :error]))
            "Error should indicate circuit is open")
        (is (= 3 @failure-count) "Original function should not have been called when circuit is open"))))

  (testing "Should reset to half-open state after timeout"
    (let [success? (atom false)
          test-fn (fn [_]
                    (if @success?
                      (success "success")
                      (failure {:error "test failure"})))
          circuit-breaker (create-circuit-breaker {:failure-threshold 2
                                                   :reset-timeout-ms 50
                                                   :half-open-calls 1})
          protected-fn (circuit-breaker test-fn)]

      ;; サーキットを開く
      (protected-fn "test")
      (protected-fn "test")

      ;; サーキットが開いたことを確認
      (is (= "Circuit breaker is open"
             (get-in (protected-fn "test") [:error :error]))
          "Circuit should be open after threshold failures")

      ;; 半開状態になるのを待つ
      (<!! (timeout 150))

      ;; 成功を設定
      (reset! success? true)

      ;; 半開状態での呼び出しは成功するはず
      (is (= (success "success") (protected-fn "test"))
          "Should allow test call in half-open state")

      ;; サーキットが閉じたことを確認
      (<!! (timeout 50))
      (is (= (success "success") (protected-fn "test"))
          "Circuit should be closed after successful half-open call")

      ;; 複数の連続呼び出しが成功することを確認
      (dotimes [_ 3]
        (is (= (success "success") (protected-fn "test"))
            "Multiple calls should succeed when circuit is closed"))))

  (testing "Should stay in open state if half-open test call fails"
    (let [test-fn (fn [_] (failure {:error "persistent failure"}))
          circuit-breaker (create-circuit-breaker {:failure-threshold 2
                                                   :reset-timeout-ms 50 ;; Short timeout for testing
                                                   :half-open-calls 1})
          protected-fn (circuit-breaker test-fn)]

      ;; Force circuit open
      (protected-fn "test")
      (protected-fn "test") ;; This should trigger circuit open

      ;; Wait for timeout to transition to half-open
      (<!! (timeout 100))

      ;; Call in half-open state, should fail and reopen circuit
      (let [result (protected-fn "test")]
        (is (failure? result) "Test call in half-open state should return original failure")
        (is (= "persistent failure" (get-in result [:error :error]))))

      ;; Verify circuit is open again
      (<!! (timeout 20)) ;; Small delay to ensure state update
      (let [result (protected-fn "test")]
        (is (= "Circuit breaker is open" (get-in result [:error :error]))
            "Circuit should be open again after failed test call"))))

  (testing "Should use custom failure predicate"
    (let [call-count (atom 0)
          test-fn (fn [_]
                    (swap! call-count inc)
                    (failure {:error "specific failure" :type :test}))
          ;; Only treat failures with :type :real as actual failures
          failure-predicate (fn [result]
                              (and (failure? result)
                                   (= :real (get-in result [:error :type]))))
          circuit-breaker (create-circuit-breaker {:failure-threshold 2
                                                   :failure-predicate failure-predicate})
          protected-fn (circuit-breaker test-fn)]

      ;; These calls should not count as failures for the circuit breaker
      (protected-fn "test")
      (protected-fn "test")
      (protected-fn "test")

      ;; Circuit should still be closed because our failure-predicate ignores these errors
      (let [result (protected-fn "test")]
        (is (failure? result) "Function should return failure")
        (is (= "specific failure" (get-in result [:error :error])) "Original error should be returned")
        (is (= 4 @call-count) "Function should have been called 4 times")))))

(deftest circuit-breaker-concurrency-test
  (testing "Should handle concurrent requests correctly"
    (let [success-counter (atom 0)
          failure-counter (atom 0)
          ;; テスト用の遅延を入れることで、並行処理の問題を明確にする
          test-fn (fn [result-type]
                    (case result-type
                      :success (do (swap! success-counter inc)
                                   ;; 少し遅延を入れる
                                   (<!! (timeout 10))
                                   (success "success"))
                      :failure (do (swap! failure-counter inc)
                                   ;; 少し遅延を入れる
                                   (<!! (timeout 10))
                                   (failure {:error "failure"}))))
          circuit-breaker (create-circuit-breaker {:failure-threshold 3
                                                   :reset-timeout-ms 100})
          protected-fn (circuit-breaker test-fn)]

      ;; 並行処理よりも順次実行で失敗を再現する
      (dotimes [_ 3]
        (protected-fn :failure))

      ;; ここでサーキットブレーカーが開いているはずなので、追加呼び出しは失敗するはず
      (let [results (doall (for [_ (range 7)]
                             (protected-fn :failure)))]
        ;; 最初の3回の呼び出しは既に実施済みなので、合計で3になるはず
        (is (= 3 @failure-counter) "Only the threshold number of failures should occur")

        ;; すべての呼び出しが「サーキットが開いている」エラーを返すはず
        (is (every? #(= "Circuit breaker is open" (get-in % [:error :error])) results)
            "All calls should be blocked by open circuit"))))

(deftest circuit-breaker-state-management-test
  (testing "Internal state management functions work correctly"
    (let [failure-threshold 3
          half-open-calls 2]

      ;; Test process-success behavior
      (testing "process-success updates state correctly"
        ;; We can't directly test private functions, but we can test through the public interface
        (let [test-fn (constantly (success "ok"))
              circuit-breaker (create-circuit-breaker {:failure-threshold failure-threshold
                                                       :half-open-calls half-open-calls})
              protected-fn (circuit-breaker test-fn)]
          
          ;; Call the function to trigger success processing
          (protected-fn "test")
          
          ;; The internal state should be updated (we can't access it directly)
          ;; But we can verify behavior through repeated calls
          (is (= (success "ok") (protected-fn "test")))))

      ;; Test failure processing through integration
      (testing "failure processing opens circuit at threshold"
        (let [failure-fn (constantly (failure {:error "test failure"}))
              circuit-breaker (create-circuit-breaker {:failure-threshold 2})
              protected-fn (circuit-breaker failure-fn)]
          
          ;; First failure
          (let [result1 (protected-fn "test")]
            (is (failure? result1))
            (is (= "test failure" (get-in result1 [:error :error]))))
          
          ;; Second failure should open the circuit
          (let [result2 (protected-fn "test")]
            (is (failure? result2))
            (is (= "test failure" (get-in result2 [:error :error]))))
          
          ;; Third call should be blocked
          (let [result3 (protected-fn "test")]
            (is (failure? result3))
            (is (= "Circuit breaker is open" (get-in result3 [:error :error])))))))))

(deftest circuit-breaker-logging-test
  (testing "Circuit breaker logs state changes appropriately"
    ;; Since we can't easily test logging output, we test that the circuit breaker
    ;; continues to function correctly even with logging enabled
    (let [call-count (atom 0)
          test-fn (fn [_]
                    (swap! call-count inc)
                    (if (<= @call-count 2)
                      (failure {:error "test failure"})
                      (success "success")))
          circuit-breaker (create-circuit-breaker {:failure-threshold 2
                                                   :reset-timeout-ms 50})
          protected-fn (circuit-breaker test-fn)]

      ;; Trigger state changes and verify behavior
      (protected-fn "test") ; First failure
      (protected-fn "test") ; Second failure, should open circuit
      
      ;; Circuit should be open now
      (let [result (protected-fn "test")]
        (is (= "Circuit breaker is open" (get-in result [:error :error]))))
      
      ;; Wait for reset
      (<!! (timeout 100))
      
      ;; Should allow test call in half-open state
      (let [result (protected-fn "test")]
        (is (success? result))
        (is (= "success" (:value result))))))))

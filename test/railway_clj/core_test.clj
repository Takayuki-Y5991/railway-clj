(ns railway-clj.core-test
  (:require
   [railway-clj.core :refer [!> <|> >-< ->error ->value attempt delay-railway either failure failure? guard lazy? success success? validate |+ |-| |>]]
   [clojure.spec.alpha :as s]
   [clojure.test :refer  [deftest is testing]]))

(s/def ::name string?)
(s/def ::age pos-int?)
(s/def ::email (s/and string? #(re-matches #".*@.*\..*" %)))
(s/def ::person (s/keys :req-un [::name ::age ::email]))

(deftest result-type-test
  (testing "Success and Failure types"
    (let [s (success "value")
          f (failure "error")]
      (is (success? s))
      (is (not (success? f)))
      (is (failure? f))
      (is (not (failure? s)))
      (is (= "value" (:value s)))
      (is (= "error" (:error f))))))

(deftest thread-success-test
  (testing "|> threads success values"
    (let [inc-fn (fn [x] (inc x))
          double-fn (fn [x] (* 2 x))
          result (|> (success 5) inc-fn double-fn)]
      (is (success? result))
      (is (= 12 (:value result)))))

  (testing "|> passes failures through unchanged"
    (let [inc-fn (fn [x] (inc x))
          error {:message "Error"}
          result (|> (failure error) inc-fn)]
      (is (failure? result))
      (is (= error (:error result))))))

(deftest thread-error-test
  (testing "|-| threads error values"
    (let [add-info (fn [e] (assoc e :info "Additional info"))
          add-timestamp (fn [e] (assoc e :timestamp "now"))
          error {:message "Error"}
          result (|-| (failure error) add-info add-timestamp)]
      (is (failure? result))
      (is (= "Additional info" (get-in result [:error :info])))
      (is (= "now" (get-in result [:error :timestamp])))))

  (testing "|-| passes successes through unchanged"
    (let [add-info (fn [e] (assoc e :info "Additional info"))
          result (|-| (success 42) add-info)]
      (is (success? result))
      (is (= 42 (:value result))))))

(deftest branch-test
  (testing ">-< branches on success"
    (let [success-fn (fn [v] (str "Success: " v))
          failure-fn (fn [e] (str "Failure: " e))
          result1 (>-< (success "good") success-fn failure-fn)
          result2 (>-< (failure "bad") success-fn failure-fn)]
      (is (= "Success: good" result1))
      (is (= "Failure: bad" result2)))))

(deftest alternative-test
  (testing "<|> provides alternative on failure"
    (let [result1 (<|> (success 1) (success 2))
          result2 (<|> (failure "error") (success 2))]
      (is (success? result1))
      (is (= 1 (:value result1)))
      (is (success? result2))
      (is (= 2 (:value result2))))))

(deftest validate-test
  (testing "validate creates success for valid data"
    (let [valid-person {:name "John" :age 30 :email "john@example.com"}
          result (validate ::person valid-person)]
      (is (success? result))
      (is (= valid-person (:value result)))))

  (testing "validate creates failure for invalid data"
    (let [invalid-person {:name "John" :age -30 :email "not-an-email"}
          result (validate ::person invalid-person)]
      (is (failure? result))
      (is (string? (get-in result [:error :details]))))))

(deftest try-railway-test
  (testing "!> catches exceptions and returns them as failures"
    (let [result1 (!> (+ 1 2))
          result2 (!> (throw (Exception. "Test exception")))]
      (is (success? result1))
      (is (= 3 (:value result1)))
      (is (failure? result2))
      (is (= "Unexpected error" (get-in result2 [:error :error])))
      (is (= "Test exception" (get-in result2 [:error :details]))))))

(deftest chain-test
  (testing "|+ chains functions together"
    (let [inc-fn (fn [x] (inc x))
          double-fn (fn [x] (* 2 x))
          fail-fn (fn [_] (failure {:message "Failed"}))
          success-chain (|+ inc-fn double-fn)
          failure-chain (|+ inc-fn fail-fn double-fn)]

      (is (= 6 (:value (success-chain 2))))

      (let [failure-result (failure-chain 2)]
        (is (failure? failure-result))
        (is (= {:message "Failed"} (:error failure-result)))))))

(deftest either-test
  (testing "either selects function based on predicate"
    (let [even? (fn [x] (even? x))
          double (fn [x] (* 2 x))
          triple (fn [x] (* 3 x))
          choose-fn (either even? double triple)]

      (is (= 4 (choose-fn 2)))
      (is (= 9 (choose-fn 3))))))

(deftest guard-test
  (testing "guard creates success for valid conditions"
    (let [positive? (fn [x] (> x 0))
          check-positive (guard positive? "Value must be positive")]

      (is (success? (check-positive 5)))
      (is (= 5 (:value (check-positive 5))))

      (let [result (check-positive -5)]
        (is (failure? result))
        (is (= "Value must be positive" (get-in result [:error :error])))))))

(deftest attempt-test
  (testing "attempt catches exceptions in functions"
    (let [risky-fn (fn [x] (/ 10 x))
          handle-error (fn [e] (failure {:message (.getMessage e)}))
          safe-fn (attempt risky-fn handle-error)]

      (is (= 5 (safe-fn 2)))

      (let [result (safe-fn 0)]
        (is (failure? result))
        (is (= "Divide by zero" (get-in result [:error :message]))))))

(deftest delay-railway-test
  (testing "delay-railway creates lazy railway result based on evaluation"
    (let [eval-count (atom 0)
          success-fn (fn []
                       (swap! eval-count inc)
                       (success "delayed success"))
          failure-fn (fn []
                       (swap! eval-count inc)
                       (failure "delayed failure"))
          lazy-success-result (delay-railway (success-fn))
          lazy-failure-result (delay-railway (failure-fn))]

      ;; Initially, functions should not be evaluated
      (is (= 0 @eval-count))

      ;; Force evaluation by calling the returned function
      (let [success-lazy (lazy-success-result)
            failure-lazy (lazy-failure-result)]
        
        ;; Now functions should be evaluated
        (is (= 2 @eval-count))
        
        ;; Check that lazy results are created correctly
        (is (lazy? success-lazy))
        (is (lazy? failure-lazy))
        (is (success? success-lazy))
        (is (failure? failure-lazy))
        
        ;; Force evaluation to get actual values
        (is (= "delayed success" (->value success-lazy)))
        (is (= "delayed failure" (->error failure-lazy))))))))
(ns railway-clj.core-test
  (:require
   [railway-clj.core :refer [ok err ok? err? unwrap then recover branch or-else pipeline ->result]]
   [clojure.test :refer [deftest is testing]]))

;; ---------------------------------------------------------------------------
;; 2.1 ok, err creation
;; ---------------------------------------------------------------------------
(deftest ok-test
  (testing "ok returns [:ok value]"
    (is (= [:ok 42] (ok 42)))
    (is (= [:ok nil] (ok nil)))
    (is (= [:ok "hello"] (ok "hello")))
    (is (= [:ok {:a 1}] (ok {:a 1})))))

(deftest err-test
  (testing "err returns [:err reason]"
    (is (= [:err "not found"] (err "not found")))
    (is (= [:err {:type :validation :msg "invalid"}]
           (err {:type :validation :msg "invalid"})))
    (is (= [:err nil] (err nil)))))

;; ---------------------------------------------------------------------------
;; 2.2 ok?, err? predicates
;; ---------------------------------------------------------------------------
(deftest ok?-test
  (testing "ok? returns true for [:ok ...]"
    (is (true? (ok? (ok 1))))
    (is (true? (ok? (ok nil)))))
  (testing "ok? returns false for [:err ...]"
    (is (false? (ok? (err "fail"))))))

(deftest err?-test
  (testing "err? returns true for [:err ...]"
    (is (true? (err? (err "fail"))))
    (is (true? (err? (err nil)))))
  (testing "err? returns false for [:ok ...]"
    (is (false? (err? (ok 1))))))

;; ---------------------------------------------------------------------------
;; 2.3 unwrap
;; ---------------------------------------------------------------------------
(deftest unwrap-test
  (testing "unwrap extracts value from ok"
    (is (= 42 (unwrap (ok 42)))))
  (testing "unwrap extracts reason from err"
    (is (= "fail" (unwrap (err "fail")))))
  (testing "unwrap handles nil in both ok and err"
    (is (nil? (unwrap (ok nil))))
    (is (nil? (unwrap (err nil))))))

;; ---------------------------------------------------------------------------
;; 2.4 then
;; ---------------------------------------------------------------------------
(deftest then-test
  (testing "applies f to success value"
    (is (= (ok 6) (then (ok 5) inc))))
  (testing "passes failure through unchanged"
    (is (= (err "fail") (then (err "fail") inc))))
  (testing "uses result as-is when f returns a result"
    (is (= (err "too big")
           (then (ok 10) #(if (> % 5) (err "too big") (ok %))))))
  (testing "wraps plain value in ok"
    (is (= (ok "hello!") (then (ok "hello") #(str % "!"))))))

;; ---------------------------------------------------------------------------
;; 2.5 recover
;; ---------------------------------------------------------------------------
(deftest recover-test
  (testing "applies f to failure reason"
    (is (= (ok "recovered")
           (recover (err "fail") (fn [_] (ok "recovered"))))))
  (testing "passes success through unchanged"
    (is (= (ok 42) (recover (ok 42) (fn [_] (ok "recovered")))))))

;; ---------------------------------------------------------------------------
;; 2.6 branch
;; ---------------------------------------------------------------------------
(deftest branch-test
  (testing "calls on-ok for success"
    (is (= "value: 42"
           (branch (ok 42)
                   #(str "value: " %)
                   #(str "error: " %)))))
  (testing "calls on-err for failure"
    (is (= "error: not found"
           (branch (err "not found")
                   #(str "value: " %)
                   #(str "error: " %))))))

;; ---------------------------------------------------------------------------
;; 2.7 or-else
;; ---------------------------------------------------------------------------
(deftest or-else-test
  (testing "returns alternative on failure"
    (is (= (ok 0) (or-else (err "fail") (ok 0)))))
  (testing "passes success through"
    (is (= (ok 42) (or-else (ok 42) (ok 0))))))

;; ---------------------------------------------------------------------------
;; 2.8 pipeline
;; ---------------------------------------------------------------------------
(deftest pipeline-test
  (testing "chains functions on success"
    (let [process (pipeline inc #(* % 2))]
      (is (= (ok 12) (process 5)))))
  (testing "short-circuits on failure"
    (let [fail-if-big #(if (> % 5) (err "too big") %)
          process (pipeline inc fail-if-big #(* % 2))]
      (is (= (err "too big") (process 5)))
      (is (= (ok 6) (process 2)))))
  (testing "composed function is reusable"
    (let [process (pipeline inc #(* % 2))]
      (is (= (ok 12) (process 5)))
      (is (= (ok 6) (process 2))))))

;; ---------------------------------------------------------------------------
;; 2.9 ->result
;; ---------------------------------------------------------------------------
(deftest ->result-test
  (testing "returns [:ok value] on success"
    (is (= (ok 3) (->result (+ 1 2)))))
  (testing "returns [:err ...] on exception"
    (let [result (->result (/ 1 0))]
      (is (err? result))
      (is (= :exception (:type (unwrap result))))
      (is (string? (:message (unwrap result)))))))

;; ---------------------------------------------------------------------------
;; 2.10 -> + then inline pipeline
;; ---------------------------------------------------------------------------
(deftest threading-with-then-test
  (testing "-> threading with then"
    (is (= (ok 12)
           (-> (ok 5)
               (then inc)
               (then #(* % 2))))))
  (testing "short-circuits on failure in threading"
    (is (= (err "negative")
           (-> (ok -1)
               (then #(if (neg? %) (err "negative") %))
               (then inc))))))

;; ---------------------------------------------------------------------------
;; Alias tests
;; ---------------------------------------------------------------------------
(deftest alias-test
  (testing ">> is the same as then"
    (is (= (ok 6) (railway-clj.core/>> (ok 5) inc))))
  (testing "<< is the same as recover"
    (is (= (ok "fixed") (railway-clj.core/<< (err "fail") (fn [_] (ok "fixed"))))))
  (testing ">< is the same as branch"
    (is (= "ok:42" (railway-clj.core/>< (ok 42) #(str "ok:" %) #(str "err:" %)))))
  (testing "|? is the same as or-else"
    (is (= (ok 0) (railway-clj.core/|? (err "fail") (ok 0)))))
  (testing ">>> is the same as pipeline"
    (let [process (railway-clj.core/>>> inc #(* % 2))]
      (is (= (ok 12) (process 5))))))

(ns railway-clj.core
  (:require [clojure.spec.alpha :as s]))

; -----------------------------------------------------------------------------
;; Result Types
;; -----------------------------------------------------------------------------
;; These types represent the core concept of Railway Oriented Programming (ROP).
;; Each operation returns either a Success or Failure, allowing us to chain
;; operations while handling both the "happy path" and error cases elegantly.
(defrecord Success [value])
(defrecord Failure [error])
(defrecord LazySuccess [thunk])
(defrecord LazyFailure [thunk])

;; Creates a Success type containing the provided value
;; Used when an operation completes successfully
(defn success [value]
  (->Success value))

;; Creates a Failure type containing the error information
;; Used when an operation fails
(defn failure [error]
  (->Failure error))

;; Checks if a result is a Success instance
(defn success? [result]
  (or (instance? Success result)
      (instance? LazySuccess result)))

;; Checks if a result is a Failure instance
(defn failure? [result]
  (or (instance? Failure result)
      (instance? LazyFailure result)))

;; creates a lazy success that will evaluate the thunk when needed
(defn lazy-success [thunk]
  (->LazySuccess thunk))

;; creates a lazy failure that will evaluate the thunk when needed
(defn lazy-failure [thunk]
  (->LazyFailure thunk))

;; Checks if a result is lazy
(defn lazy? [result]
  (or (instance? LazySuccess result)
      (instance? LazyFailure result)))

;; Force evaluation of lazy results
(defn force-lazy [result]
  (cond
    (instance? LazySuccess result) (success ((:thunk result)))
    (instance? LazyFailure result) (failure ((:thunk result)))
    :else result))

;; Retrieves the value from a Success or failure
(defn ->value [result]
  (cond
    (instance? Success result) (:value result)
    (instance? LazySuccess result) ((:thunk result))
    :else (throw (Exception. "Cannot get value from non-result"))))

;; Retrieves the error from a Failure or LazyFailure
(defn ->error [result]
  (cond
    (instance? Failure result) (:error result)
    (instance? LazyFailure result) ((:thunk result))
    :else (throw (Exception. "Cannot get error from non-Failure result"))))

;; -----------------------------------------------------------------------------
;; Core Railway Operations
;; -----------------------------------------------------------------------------
;; These macros provide the fundamental operations for Railway Oriented
;; Programming, allowing you to compose functions while handling success
;; and failure states automatically.
(defmacro |>
  "Thread success value through functions.
     Similar to the -> threading macro, but only applies functions
     if the input is a Success. Otherwise, passes the Failure through unchanged.
     Handles lazy results by preserving laziness.

     Example:
     (|> (success 5) inc double) => (success 12)
     (|> (failure :error) inc double) => (failure :error)
     (|> (lazy-success #(expensive-calc)) inc) => (lazy-success #(inc (expensive-calc)))"
  [value & fns]
  `(let [result# ~value]
     (cond
       (instance? Success result#)
       (success (-> (:value result#)
                    ~@(map list fns)))

       (instance? LazySuccess result#)
       (lazy-success #(-> ((:thunk result#))
                          ~@(map list fns)))

       :else result#)))

(defmacro |-|
  "Thread error value through functions.
   Only applies functions if the input is a Failure. Otherwise,
   passes the Success through unchanged.
   Handles lazy results by preserving laziness.

   Example:
   (|-| (failure {:msg \"Error\"})
        #(assoc % :timestamp \"now\")) => (failure {:msg \"Error\" :timestamp \"now\"})
   (|-| (success 42) #(assoc % :info \"ignored\")) => (success 42)
   (|-| (lazy-failure #(->error)) add-timestamp) => (lazy-failure #(add-timestamp (->error)))"
  [value & fns]
  `(let [result# ~value]
     (cond
       (instance? Failure result#)
       (failure (-> (:error result#)
                    ~@(map list fns)))

       (instance? LazyFailure result#)
       (lazy-failure #(-> ((:thunk result#))
                          ~@(map list fns)))

       :else result#)))

(defmacro >-<
  "Branch processing based on success/failure.
     Takes a result and two functions - one for handling success,
     one for handling failure. This is the terminal operation in a
     railway chain. Forces evaluation of lazy results.

     Example:
     (>-< (success 42)
          #(str \"Success: \" %)
          #(str \"Error: \" %)) => \"Success: 42\"
     (>-< (lazy-success #(expensive-calc))
          #(str \"Success: \" %)
          #(str \"Error: \" %)) => \"Success: [result of expensive-calc]\""
  [value success-fn failure-fn]
  `(let [result# ~value
         forced# (force-lazy result#)]
     (if (success? forced#)
       (~success-fn (->value forced#))
       (~failure-fn (->error forced#)))))

(defmacro <|>
  "Try alternative on failure.
     If the value is a Failure, evaluates and returns the alternative.
     Otherwise, returns the original Success unchanged.
     Similar to the 'or' operation in many languages.
     Preserves laziness for Success results, but forces evaluation of Failure
     to determine if the alternative should be used.

     Example:
     (<|> (failure \"error\") (success 42)) => (success 42)
     (<|> (success 1) (success 2)) => (success 1)
     (<|> (lazy-failure #(->error)) (lazy-success #(fallback))) => (lazy-success #(fallback))"
  [value alternative]
  `(let [result# ~value]
     (cond
       ;; For immediate failures, use the alternative
       (instance? Failure result#)
       ~alternative

       ;; For lazy failures, we need to check if they're actually failures
       (instance? LazyFailure result#)
       (let [forced# (force-lazy result#)]
         (if (failure? forced#)
           ~alternative
           forced#))

       ;; For success (lazy or not), keep it unchanged
       :else result#)))

;; -----------------------------------------------------------------------------
;; Validation Utilities
;; -----------------------------------------------------------------------------
;; Integrate with clojure.spec to provide validation capabilities

(defn validate
  "Validates a value against a spec, returning a railway result.
     Returns Success with the value if valid, otherwise a Failure
     with detailed error information.

     Example:
     (s/def ::person (s/keys :req-un [::name ::age]))
     (validate ::person {:name \"John\" :age 30}) => (success {:name \"John\" :age 30})
     (validate ::person {}) => (failure {:error \"Validation failed\" :details \"...\"})"
  [spec value]
  (if (s/valid? spec value)
    (success value)
    (failure {:error "Validation failed"
              :details (s/explain-str spec value)})))

;; -----------------------------------------------------------------------------
;; Error Handling
;; -----------------------------------------------------------------------------
;; Provides exception handling in a railway-compatible format
(defmacro !>
  "Railway-oriented try/catch.
     Executes the body and returns a Success with the result.
     If an exception occurs, returns a Failure with error details.

     Example:
     (!> (/ 10 5)) => (success 2)
     (!> (/ 10 0)) => (failure {:error \"Unexpected error\" :details \"Divide by zero\"})"
  [& body]
  `(try
     (success ~@body)
     (catch Exception e#
       (failure {:error "Unexpected error"
                 :details (.getMessage e#)}))))

;; Composition utilities
(defn |+
  "Chains multiple functions in a railway pattern.
     Returns a new function that applies each function in sequence,
     stopping and returning the first Failure encountered.
     Each function should either return a railway result or a plain value
     (which will be wrapped in Success).

     Example:
     (def workflow (|+ validate-user check-permissions save-user))
     (workflow user-data) => Success or Failure depending on chain results"
  [& fs]
  (fn [x]
    (reduce (fn [acc f]
              (if (success? acc)
                (let [forced# (force-lazy acc)
                      result (try (f (->value forced#))
                                  (catch Exception e
                                    (failure (.getMessage e))))]
                  (cond
                    (success? result) result
                    (failure? result) result
                    :else (success result)))
                acc))
            (success x)
            fs)))

;; -----------------------------------------------------------------------------
;; Lazy Operations
;; -----------------------------------------------------------------------------
;; Functions to support lazy evaluation in railway operations

(defmacro delay-success
  "Creates a lazy success that will only execute the body when needed.
   Useful for expensive operations that might not be needed.

   Example:
   (delay-success (expensive-calculation)) => LazySuccess
   (|> (delay-success (expensive-calculation)) process-result) => LazySuccess"
  [& body]
  `(lazy-success (fn [] ~@body)))

(defmacro delay-railway
  "Creates a lazy railway result (Success or Failure) based on the
   evaluation of the body when forced.

   Example:
   (delay-railway (validate-data data)) => LazySuccess or LazyFailure
   (|> (delay-railway (db-operation)) process-result) => Preserves laziness"
  [& body]
  `(let [thunk# (fn [] ~@body)]
     (fn []
       (let [result# (thunk#)]
         (if (success? result#)
           (lazy-success #(->value result#))
           (lazy-failure #(->error result#)))))))

(defmacro |>lazy
  "Similar to |> but ensures all operations are done lazily,
   even if the input is not lazy.

   Example:
   (|>lazy (success 5) expensive-calc format-result) => LazySuccess"
  [value & fns]
  `(let [result# ~value
         lazy-result# (if (lazy? result#)
                        result#
                        (if (success? result#)
                          (lazy-success #(->value result#))
                          (lazy-failure #(->error result#))))]
     (|> lazy-result# ~@fns)))

;; -----------------------------------------------------------------------------
;; Railway Combinators
;; -----------------------------------------------------------------------------
;; Higher-order functions for common railway patterns
(defmacro either
  "Creates a function that applies one of two functions based on a predicate.
     Similar to an if-statement, but returns a function that can be composed.

     Example:
     (def process (either even? #(* 2 %) #(+ 1 %)))
     (process 2) => 4
     (process 3) => 4"
  [pred then else]
  `(fn [x#]
     (if (~pred x#)
       (~then x#)
       (~else x#))))

(defmacro guard
  "Creates a function that succeeds if a predicate passes, otherwise fails.
     Useful for validating conditions in a railway chain.

     Example:
     (def check-positive (guard pos? \"Value must be positive\"))
     (check-positive 5) => (success 5)
     (check-positive -1) => (failure {:error \"Value must be positive\"})"
  [pred error-msg]
  `(fn [x#]
     (if (~pred x#)
       (success x#)
       (failure {:error ~error-msg}))))

(defmacro attempt
  "Creates a function that catches exceptions and handles them with the provided handler.
     Useful for wrapping functions that might throw exceptions.

     Example:
     (def safe-divide (attempt #(/ 10 %) #(failure {:error \"Division error\"})))
     (safe-divide 2) => 5
     (safe-divide 0) => (failure {:error \"Division error\"})"
  [f error-handler]
  `(fn [x#]
     (try
       (~f x#)
       (catch Exception e#
         (~error-handler e#)))))
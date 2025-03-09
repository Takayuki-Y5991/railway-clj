(ns railway-clj.async
  (:require
   [railway-clj.core :as r :refer [->value failure failure?
                                   force-lazy success
                                   success?]]
   [clojure.core.async :as async :refer [<! go]]))

;; Checks if a value is a channel (for async operations)
(defn channel? [x]
  (instance? clojure.core.async.impl.channels.ManyToManyChannel x))

;; -----------------------------------------------------------------------------
;; Async Railway Operations
;; -----------------------------------------------------------------------------
;; Extensions to support asynchronous operations using core.async

(defn unwrap-channel
  "Takes a channel containing a railway result and returns a channel
   that will contain the unwrapped value or error."
  [ch]
  (go
    (let [result (<! ch)]
      (r/>-< result identity identity))))

(defmacro |>
  "Asynchronous version of |>.
   Threads a railway result through async functions.
   Each function should return a channel containing a railway result.

   Example:
   (|> (go (success 5))
        #(go (success (inc %)))
        #(go (success (double %)))) => channel containing (success 12)"
  [ch & fns]
  `(go
     (let [result# (<! ~ch)]
       (if (success? result#)
         (loop [value# (:value result#)
                [f# & fs#] [~@fns]]
           (if f#
             (let [next-ch# (f# value#)
                   next-result# (<! next-ch#)]
               (if (success? next-result#)
                 (recur (:value next-result#) fs#)
                 next-result#))
             (success value#)))
         result#))))

(defmacro |-|
  "Asynchronous version of |-|.
   Threads a railway error through async functions.
   Each function should return a channel containing a railway result.

   Example:
   (|-| (go (failure {:msg \"Error\"}))
         #(go (failure (assoc % :timestamp \"now\")))) => channel containing updated failure"
  [ch & fns]
  `(go
     (let [result# (<! ~ch)]
       (if (failure? result#)
         (loop [error# (:error result#)
                [f# & fs#] [~@fns]]
           (if f#
             (let [next-ch# (f# error#)
                   next-result# (<! next-ch#)]
               (if (failure? next-result#)
                 (recur (:error next-result#) fs#)
                 next-result#))
             (failure error#)))
         result#))))

(defmacro >-<
  "Asynchronous version of >-<.
   Branch processing based on success/failure for async operations.
   Both success-fn and failure-fn should return channels.

   Example:
   (>-< (go (success 42))
         #(go (str \"Success: \" %))
         #(go (str \"Error: \" %))) => channel containing \"Success: 42\""
  [ch success-fn failure-fn]
  `(go
     (let [result# (<! ~ch)]
       (if (success? result#)
         (<! (~success-fn (:value result#)))
         (<! (~failure-fn (:error result#)))))))

(defmacro <|>
  "Asynchronous version of <|>.
   Try alternative on failure for async operations.
   Both value and alternative should be channels containing railway results.

   Example:
   (<|> (go (failure \"error\"))
         (go (success 42))) => channel containing (success 42)"
  [ch alternative]
  `(go
     (let [result# (<! ~ch)]
       (if (failure? result#)
         (<! ~alternative)
         result#))))

;; -----------------------------------------------------------------------------
;; Validation Utilities
;; -----------------------------------------------------------------------------
;; Integrate with clojure.spec to provide validation capabilities

(defn validate
  "Asynchronous version of validate.
   Returns a channel containing a railway result."
  [spec value]
  (go (r/validate spec value)))

;; -----------------------------------------------------------------------------
;; Error Handling
;; -----------------------------------------------------------------------------
;; Provides exception handling in a railway-compatible format

(defmacro !>
  "Asynchronous version of !>.
   Returns a channel containing a railway result."
  [& body]
  `(go
     (try
       (success ~@body)
       (catch Exception e#
         (failure {:error "Unexpected error"
                   :details (.getMessage e#)})))))

(defn |+
  "Asynchronous version of |+.
   Chains multiple async functions in a railway pattern.
   Each function should return a channel containing a railway result.

   Example:
   (def workflow (|+a validate-user-async check-permissions-async save-user-async))
   (workflow user-data) => channel containing Success or Failure"
  [& fs]
  (fn [x]
    (go
      (loop [acc (success x)
             [f & remaining] fs]
        (if (and f (success? acc))
          (let [forced# (force-lazy acc)
                result-ch (try
                            (f (->value forced#))
                            (catch Exception e
                              (go (failure {:error "Unexpected error"
                                            :details (.getMessage e)}))))
                result (<! result-ch)]
            (recur (cond
                     (success? result) result
                     (failure? result) result
                     :else (success result))
                   remaining))
          acc)))))

;; -----------------------------------------------------------------------------
;; Railway Combinators
;; -----------------------------------------------------------------------------
;; Higher-order functions for common railway patterns

(defmacro either
  "Asynchronous version of either.
   Both then and else functions should return channels."
  [pred then else]
  `(fn [x#]
     (go
       (if (~pred x#)
         (<! (~then x#))
         (<! (~else x#))))))

(defmacro guard
  "Asynchronous version of guard.
   Returns a function that returns a channel containing a railway result."
  [pred error-msg]
  `(fn [x#]
     (go
       (if (~pred x#)
         (success x#)
         (failure {:error ~error-msg})))))

(defmacro attempt
  "Asynchronous version of attempt.
   Creates a function that returns a channel and catches exceptions."
  [f error-handler]
  `(fn [x#]
     (go
       (try
         (<! (~f x#))
         (catch Exception e#
           (<! (~error-handler e#)))))))
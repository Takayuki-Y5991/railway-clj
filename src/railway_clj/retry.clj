(ns railway-clj.retry
  (:require
   [railway-clj.core :refer [success?]]
   [clojure.core.async :refer [<!! timeout]]))

(defn retryable-error? [error]
  (let [status (get-in error [:details :status])]
    (or (nil? status)
        (#{408 429 500 502 503 504} status))))

(defn retry
  "指定された回数まで操作をリトライします。

     options:
   - :max-attempts - 最大試行回数（デフォルト: 3）
   - :backoff-ms - バックオフ時間（ミリ秒）（デフォルト: 1000）
   - :backoff-factor - 指数バックオフの係数（デフォルト: 2）
   - :jitter - バックオフ時間のジッター（デフォルト: 0.1）
   - :retryable? - リトライ可能なエラーを判断する関数（デフォルト: 常にtrue）

     Example:
     (retry #(http-call \"https://example.com\")
            {:max-attempts 5
             :backoff-ms 500
             :backoff-factor 1.5
             :retryable? #(contains? #{500 503} (:status %))})
    "
  [f & [{:keys [max-attempts backoff-ms backoff-factor jitter retryable?] :or {max-attempts 3 backoff-ms 1000 backoff-factor 2 jitter 0.1 retryable? (constantly true)}}]]

  (fn [& args]
    (loop [attempts 1
           current-backoff backoff-ms]
      (let [result (apply f args)]
        (cond
          (success? result) result
          ;;  return failure, If the maximum number of attempts is reached.
          (>= attempts max-attempts) result
          ;; return failure, If the error is not retryable
          (not (retryable? (:error result))) result

          :else (do
                  (let [jitter-amount (* current-backoff jitter (- (rand) 0.5))
                        sleep-ms (+ current-backoff jitter-amount)]
                    (<!! (timeout (long sleep-ms))))
                  (recur (inc attempts) (* current-backoff backoff-factor))))))))
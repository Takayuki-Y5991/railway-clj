(ns railway-clj.circuit-breaker
  (:require
   [railway-clj.core :refer [failure failure?]]
   [clojure.core.async :as async :refer [<! chan go-loop timeout]]
   [clojure.tools.logging :as log]))

(defn- process-success
  "成功結果を処理し、状態を更新します"
  [state half-open-calls]
  (let [old-state @state
        new-state (swap! state (fn [{:keys [status] :as s}]
                                 (cond-> (assoc s :failures 0)
                                   true (update :successes inc)
                                   (and (= status :half-open)
                                        (>= (:successes s) (dec half-open-calls)))
                                   (assoc :status :closed :successes 0))))]

    ;; 状態が変化した場合にログ出力
    (when (not= (:status old-state) (:status new-state))
      (log/info "Circuit state changed from" (:status old-state) "to" (:status new-state)))))

(defn- process-failure
  "失敗結果を処理し、必要に応じて状態を更新します"
  [state reset-channel failure-threshold]
  (let [old-state @state
        new-state (swap! state (fn [{:keys [status failures] :as s}]
                                 (let [new-failures (inc failures)]
                                   (cond-> (assoc s :failures new-failures)
                                     (and (= status :closed)
                                          (>= new-failures failure-threshold))
                                     (assoc :status :open :last-opened (System/currentTimeMillis))))))]

    ;; 状態が変化した場合のみ処理
    (when (and (= (:status new-state) :open)
               (not= (:status old-state) :open))
      (log/warn "Circuit breaker opened after" (:failures new-state) "consecutive failures")
      (async/>!! reset-channel :schedule-reset))))

(defn create-circuit-breaker
  "サーキットブレーカーを作成します。
  
   options:
     - :failure-threshold - 開回路になるための連続失敗回数の閾値（デフォルト: 5）
     - :reset-timeout-ms - 半開回路状態になるまでの時間（ミリ秒）（デフォルト: 10000）
     - :half-open-calls - 半開回路状態で許可する呼び出し回数（デフォルト: 1）
     - :failure-predicate - 失敗とみなす条件を判断する関数（デフォルト: failure?）
   
   返り値は、関数を受け取りサーキットブレーカーを適用した新しい関数を返す高階関数です。
  "
  [& [{:keys [failure-threshold reset-timeout-ms half-open-calls failure-predicate]
       :or {failure-threshold 5
            reset-timeout-ms 10000
            half-open-calls 1
            failure-predicate failure?}}]]
  (let [state (atom {:status :closed
                     :failures 0
                     :successes 0})
        reset-channel (chan)]

    ;; リセット用のバックグラウンドプロセス
    (go-loop []
      (when (<! reset-channel)
        (log/debug "Scheduling circuit reset in" reset-timeout-ms "ms")
        (<! (timeout reset-timeout-ms))
        (when (= (:status @state) :open)
          (log/info "Circuit transitioning from open to half-open")
          (swap! state assoc :status :half-open :successes 0))
        (recur)))

    (log/info "Created circuit breaker:"
              {:failure-threshold failure-threshold
               :reset-timeout-ms reset-timeout-ms
               :half-open-calls half-open-calls})

    ;; サーキットブレーカーが適用された関数を返す
    (fn [f]
      (fn [& args]
        (let [{:keys [status]} @state]
          (case status
            :closed
            (let [result (apply f args)]
              (if (failure-predicate result)
                (do
                  (process-failure state reset-channel failure-threshold)
                  result)
                (do
                  (process-success state half-open-calls)
                  result)))

            :open
            (do
              (log/debug "Circuit is open, rejecting request")
              (failure {:error "Circuit breaker is open"
                        :details "Service is temporarily unavailable"}))

            :half-open
            (let [result (apply f args)]
              (log/debug "Executing test request in half-open state")
              (if (failure-predicate result)
                (do
                  (log/warn "Test request failed in half-open state, reopening circuit")
                  (swap! state assoc :status :open :failures (inc (:failures @state)))
                  (async/>!! reset-channel :schedule-reset)
                  result)
                (do
                  (process-success state half-open-calls)
                  result)))))))))
(ns railway-clj.core)

;; ---------------------------------------------------------------------------
;; データ表現
;; tagged tuple: [:ok value] / [:err reason]
;; ---------------------------------------------------------------------------

(defn ok
  "成功値を作成する。"
  [value]
  [:ok value])

(defn err
  "失敗値を作成する。"
  [reason]
  [:err reason])

(defn ok?
  "結果が成功かどうかを判定する。"
  [[tag]]
  (= :ok tag))

(defn err?
  "結果が失敗かどうかを判定する。"
  [[tag]]
  (= :err tag))

(defn unwrap
  "結果から値を取り出す。ok なら成功値、err なら失敗理由を返す。"
  [[_ v]]
  v)

;; ---------------------------------------------------------------------------
;; 操作
;; ---------------------------------------------------------------------------

(defn then
  "成功値に関数を適用する。失敗はそのまま通す。
   f が [:ok ...] / [:err ...] を返した場合はそのまま、
   plain value を返した場合は ok で包む。"
  [result f]
  (if (ok? result)
    (let [v (f (unwrap result))]
      (if (and (vector? v) (#{:ok :err} (first v)))
        v
        (ok v)))
    result))

(defn recover
  "失敗値に関数を適用する。成功はそのまま通す。"
  [result f]
  (if (err? result)
    (f (unwrap result))
    result))

(defn branch
  "成功/失敗で分岐する。"
  [result on-ok on-err]
  (if (ok? result)
    (on-ok (unwrap result))
    (on-err (unwrap result))))

(defn or-else
  "失敗時に代替値を返す。"
  [result alternative]
  (if (err? result)
    alternative
    result))

(defn pipeline
  "関数を順に適用する。最初の失敗で停止。"
  [& fs]
  (fn [x]
    (reduce then (ok x) fs)))

;; ---------------------------------------------------------------------------
;; 例外変換
;; ---------------------------------------------------------------------------

(defmacro ->result
  "例外を catch して [:err ...] に変換する。
   正常終了時は [:ok value] を返す。"
  [& body]
  `(try
     (ok (do ~@body))
     (catch Exception e#
       (err {:type :exception
             :message (.getMessage e#)}))))

;; ---------------------------------------------------------------------------
;; エイリアス（記号版）
;; ---------------------------------------------------------------------------

(def >> "then のエイリアス。成功値を次へ流す。" then)
(def << "recover のエイリアス。失敗から戻す。" recover)
(def >< "branch のエイリアス。成功/失敗で分岐する。" branch)
(def |? "or-else のエイリアス。失敗時に代替値を返す。" or-else)
(def >>> "pipeline のエイリアス。関数を順に適用する。" pipeline)

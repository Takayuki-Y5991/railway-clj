# railway-clj

Railway Oriented Programming (ROP) for Clojure - エラーハンドリングを簡潔かつエレガントに行うためのライブラリです。

## 概要

railway-cljは、関数型プログラミングにおけるRailway Oriented Programming（ROP）パターンをClojureで実装したものです。このパターンは「幸福パス」と「エラーパス」を明示的に区別し、値の変換を連続して行うパイプラインを構築する際に特に有用です。

主な機能：

- 基本的なROP操作（Success/Failure値の取り扱い）
- 遅延評価（Lazy evaluation）
- 非同期処理（core.async統合）
- サーキットブレーカーパターン
- リトライ機能

## インストール

Leiningenプロジェクトの`:dependencies`に以下を追加してください：

```clojure
[railway-clj "0.1.0-SNAPSHOT"]
```

## 使用例

### 基本的な使い方

```clojure
(ns example.core
  (:require [railway-clj.core :as r]))

;; 成功パスの値をスレッディング
(r/|> (r/success 5)
      inc
      #(* % 2))
;; => #railway_clj.core.Success{:value 12}

;; エラーハンドリング
(r/>-< (r/|> (r/success 5)
             inc
             #(* % 2))
       #(str "結果: " %)
       #(str "エラー: " (:error %)))
;; => "結果: 12"
```

### 非同期処理

```clojure
(ns example.async
  (:require [railway-clj.async :as ra]
            [railway-clj.core :as r]
            [clojure.core.async :refer [<! go]]))

(defn async-process [x]
  (go (r/success (inc x))))

(go
  (let [result (<! (ra/|> (go (r/success 5))
                          async-process
                          #(go (r/success (* % 2)))))]
    (println result)))
;; => #railway_clj.core.Success{:value 12}
```

### サーキットブレーカー

```clojure
(ns example.circuit-breaker
  (:require [railway-clj.circuit-breaker :as cb]
            [railway-clj.core :as r]))

;; サーキットブレーカーを作成
(def breaker (cb/create-circuit-breaker
               {:failure-threshold 3
                :reset-timeout-ms 5000}))

;; 外部サービス呼び出しをラップ
(def protected-call
  (breaker
    (fn [url]
      (try
        (let [response (http-get url)]
          (if (>= (:status response) 400)
            (r/failure {:error "HTTP error" :details response})
            (r/success response)))
        (catch Exception e
          (r/failure {:error "Connection error" :details (.getMessage e)}))))))
```

### リトライ機能

```clojure
(ns example.retry
  (:require [railway-clj.retry :as retry]
            [railway-clj.core :as r]))

;; リトライ可能な関数を作成
(def with-retry
  (retry/retry
    (fn [url]
      (try
        (let [response (http-get url)]
          (if (>= (:status response) 400)
            (r/failure {:error "HTTP error" :details response})
            (r/success response)))
        (catch Exception e
          (r/failure {:error "Connection error" :details (.getMessage e)}))))
    {:max-attempts 5
     :backoff-ms 1000
     :backoff-factor 2
     :retryable? retry/retryable-error?}))
```
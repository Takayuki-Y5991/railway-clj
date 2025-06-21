# railway-clj

[![Version](https://img.shields.io/badge/version-0.1.0-blue.svg)](https://github.com/Takayuki-Y5991/railway-clj)
[![Coverage](https://img.shields.io/badge/coverage-86.28%25-green.svg)](https://github.com/Takayuki-Y5991/railway-clj)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](https://opensource.org/licenses/MIT)

Railway Oriented Programming (ROP) library for Clojure - A library for concise and elegant error handling.

## Overview

railway-clj is a Clojure implementation of the Railway Oriented Programming (ROP) pattern from functional programming. This pattern explicitly distinguishes between "happy path" and "error path" flows, making it particularly useful for building pipelines that perform sequential value transformations.

Key Features:

- Basic ROP operations (Success/Failure value handling)
- Lazy evaluation
- Asynchronous processing (core.async integration)
- Circuit breaker pattern
- Retry mechanisms

## Installation

Add the following to your Leiningen project's `:dependencies`:

```clojure
[railway-clj "0.1.0"]
```

## Usage

### Basic Usage

The core of railway-clj is the Success/Failure pattern that allows you to chain operations while automatically handling errors:

```clojure
(ns example.core
  (:require [railway-clj.core :as r]))

;; Threading success values through a pipeline
(r/|> (r/success 5)
      inc
      #(* % 2))
;; => #railway_clj.core.Success{:value 12}

;; Error handling with pattern matching
(r/>-< (r/|> (r/success 5)
             inc
             #(* % 2))
       #(str "Result: " %)
       #(str "Error: " (:error %)))
;; => "Result: 12"

;; When an error occurs, it short-circuits the pipeline
(r/|> (r/failure {:error "Invalid input"})
      inc
      #(* % 2))
;; => #railway_clj.core.Failure{:error {:error "Invalid input"}}
```

### Working with Collections

```clojure
;; Map over collections with error handling
(r/map-success #(/ 10 %) [1 2 5 10])
;; => (#railway_clj.core.Success{:value 10} 
;;     #railway_clj.core.Success{:value 5} 
;;     #railway_clj.core.Success{:value 2} 
;;     #railway_clj.core.Success{:value 1})

;; Validate and transform data
(defn validate-positive [x]
  (if (pos? x)
    (r/success x)
    (r/failure {:error "Must be positive" :value x})))

(r/|> (r/success -5)
      validate-positive
      #(* % 2))
;; => #railway_clj.core.Failure{:error {:error "Must be positive", :value -5}}
```

### Asynchronous Processing

railway-clj integrates seamlessly with core.async for asynchronous workflows:

```clojure
(ns example.async
  (:require [railway-clj.async :as ra]
            [railway-clj.core :as r]
            [clojure.core.async :refer [<! go chan >!]]))

(defn async-increment [x]
  (go (r/success (inc x))))

(defn async-multiply [x]
  (go (r/success (* x 2))))

;; Async pipeline
(go
  (let [result (<! (ra/|> (go (r/success 5))
                          async-increment
                          async-multiply))]
    (println result)))
;; => #railway_clj.core.Success{:value 12}

;; Error handling in async context
(defn async-divide [x y]
  (go 
    (if (zero? y)
      (r/failure {:error "Division by zero"})
      (r/success (/ x y)))))

(go
  (let [result (<! (ra/|> (go (r/success 10))
                          #(async-divide % 0)))]
    (println result)))
;; => #railway_clj.core.Failure{:error {:error "Division by zero"}}
```

### Circuit Breaker Pattern

Protect your application from cascading failures with the circuit breaker pattern:

```clojure
(ns example.circuit-breaker
  (:require [railway-clj.circuit-breaker :as cb]
            [railway-clj.core :as r]))

;; Create a circuit breaker
(def breaker (cb/create-circuit-breaker
               {:failure-threshold 3      ; Open after 3 failures
                :reset-timeout-ms 5000    ; Try again after 5 seconds
                :half-open-calls 1}))     ; Test with 1 call when half-open

;; Wrap external service calls
(defn external-api-call [url]
  (try
    ;; Simulate HTTP call
    (let [response {:status 200 :body "OK"}]
      (if (>= (:status response) 400)
        (r/failure {:error "HTTP error" :status (:status response)})
        (r/success response)))
    (catch Exception e
      (r/failure {:error "Connection error" :message (.getMessage e)}))))

(def protected-call (breaker external-api-call))

;; Use the protected call
(protected-call "https://api.example.com/data")
;; => #railway_clj.core.Success{:value {:status 200, :body "OK"}}

;; When circuit is open, calls fail fast
;; #railway_clj.core.Failure{:error {:error "Circuit breaker is open"}}
```

### Retry Mechanisms

Add resilience with configurable retry logic:

```clojure
(ns example.retry
  (:require [railway-clj.retry :as retry]
            [railway-clj.core :as r]))

;; Create a retrying function
(def resilient-api-call
  (retry/retry
    (fn [url]
      (try
        ;; Simulate unreliable network call
        (if (< (rand) 0.7)  ; 70% failure rate
          (r/failure {:error "Network timeout"})
          (r/success {:data "Important data"}))
        (catch Exception e
          (r/failure {:error "Unexpected error" :message (.getMessage e)}))))
    {:max-attempts 5           ; Try up to 5 times
     :backoff-ms 1000         ; Start with 1 second delay
     :backoff-factor 2        ; Double the delay each time
     :retryable? retry/retryable-error?}))

;; Use the resilient function
(resilient-api-call "https://unreliable-api.com/data")
;; Will retry on failures with exponential backoff
;; => #railway_clj.core.Success{:value {:data "Important data"}}
```

### Combining Patterns

You can combine all these patterns for robust error handling:

```clojure
(ns example.combined
  (:require [railway-clj.core :as r]
            [railway-clj.async :as ra]
            [railway-clj.circuit-breaker :as cb]
            [railway-clj.retry :as retry]
            [clojure.core.async :refer [go <!]]))

;; Create a fully protected async service call
(def protected-service
  (-> (fn [request]
        (go
          ;; Your actual service logic here
          (if (valid? request)
            (r/success (process-request request))
            (r/failure {:error "Invalid request"}))))
      (retry/retry {:max-attempts 3 :backoff-ms 500})
      (cb/create-circuit-breaker {:failure-threshold 5 :reset-timeout-ms 10000})))

;; Use in an async pipeline
(go
  (let [result (<! (ra/|> (go (r/success {:user-id 123}))
                          protected-service
                          #(go (r/success (format-response %)))))]
    (r/>-< result
           #(println "Success:" %)
           #(println "Failed:" (:error %)))))
```

## API Reference

### Core Functions

- `success` - Create a success value
- `failure` - Create a failure value  
- `|>` - Pipeline operator for chaining operations
- `>-<` - Pattern match on success/failure
- `map-success` - Map function over collection, handling errors

### Async Functions

- `ra/|>` - Async pipeline operator
- `ra/>-<` - Async pattern matching

### Circuit Breaker

- `create-circuit-breaker` - Create a circuit breaker with configuration
- States: `:closed`, `:open`, `:half-open`

### Retry

- `retry` - Wrap function with retry logic
- `retryable-error?` - Default predicate for retryable errors

## Testing

Run the test suite:

```bash
lein test
```

Run with coverage (requires 70% minimum):

```bash
lein cloverage
```

Current test coverage: **86.28%** (Forms) / **96.79%** (Lines)

Coverage breakdown by namespace:
- `railway-clj.core`: 99.27% forms, 96.70% lines
- `railway-clj.retry`: 100.00% forms, 100.00% lines  
- `railway-clj.async`: 80.60% forms, 91.67% lines
- `railway-clj.circuit-breaker`: 79.68% forms, 100.00% lines

## License

MIT License - see LICENSE file for details.
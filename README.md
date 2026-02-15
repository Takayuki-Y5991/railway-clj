# railway-clj

[![Version](https://img.shields.io/badge/version-0.2.0-blue.svg)](https://github.com/Takayuki-Y5991/railway-clj)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](https://opensource.org/licenses/MIT)

Minimal Railway Oriented Programming for Clojure.

## Design Principles

- Plain data only (no defrecord)
- Functions over macros (`->result` is the only macro)
- Zero dependencies (`clojure.core` only)

## Installation

```clojure
[org.clojars.konkon/railway-clj "0.2.0"]
```

## Data Representation

Results are expressed as tagged tuples:

```clojure
[:ok value]    ;; success
[:err reason]  ;; failure
```

Plain data, so `assoc`, `merge`, `print`, `read` all work as expected.

## API

### Data Creation & Predicates

```clojure
(require '[railway-clj.core :as r])

(r/ok 42)           ;; => [:ok 42]
(r/err "not found") ;; => [:err "not found"]

(r/ok? (r/ok 42))    ;; => true
(r/err? (r/err "x")) ;; => true

(r/unwrap (r/ok 42))        ;; => 42
(r/unwrap (r/err "reason"))  ;; => "reason"
```

### then — Transform Success Values

```clojure
(r/then (r/ok 5) inc)
;; => [:ok 6]

(r/then (r/err "fail") inc)
;; => [:err "fail"]  (passes through unchanged)

;; When f returns a result, it is used as-is
(r/then (r/ok 10) #(if (> % 5) (r/err "too big") (r/ok %)))
;; => [:err "too big"]
```

### recover — Transform Failure Values

```clojure
(r/recover (r/err "fail") (fn [_] (r/ok "default")))
;; => [:ok "default"]

(r/recover (r/ok 42) (fn [_] (r/ok "default")))
;; => [:ok 42]  (passes through unchanged)
```

### branch — Branch on Success/Failure

```clojure
(r/branch (r/ok 42)
          #(str "value: " %)
          #(str "error: " %))
;; => "value: 42"
```

### or-else — Provide Alternative on Failure

```clojure
(r/or-else (r/err "fail") (r/ok 0))
;; => [:ok 0]

(r/or-else (r/ok 42) (r/ok 0))
;; => [:ok 42]
```

### pipeline — Function Composition

```clojure
(def process (r/pipeline inc #(* % 2)))

(process 5)  ;; => [:ok 12]
(process 2)  ;; => [:ok 6]

;; Short-circuits on failure
(def safe-process
  (r/pipeline
    inc
    #(if (> % 5) (r/err "too big") %)
    #(* % 2)))

(safe-process 2)  ;; => [:ok 6]
(safe-process 5)  ;; => [:err "too big"]
```

### -> + then — Inline Pipeline

```clojure
(-> (r/ok 5)
    (r/then inc)
    (r/then #(* % 2)))
;; => [:ok 12]
```

### ->result — Exception Conversion

```clojure
(r/->result (/ 10 5))
;; => [:ok 2]

(r/->result (/ 10 0))
;; => [:err {:type :exception, :message "Divide by zero"}]
```

## Aliases (Symbolic)

All operation functions have symbolic aliases. They are identical functions bound via `def`.

| Name | Symbol | Meaning |
|---|---|---|
| `then` | `>>` | Flow success value forward |
| `recover` | `<<` | Recover from failure |
| `branch` | `><` | Branch on success/failure |
| `or-else` | `\|?` | Return alternative on failure |
| `pipeline` | `>>>` | Compose functions sequentially |

```clojure
(require '[railway-clj.core :as r])

;; Readable names
(-> (r/ok 5)
    (r/then inc)
    (r/then #(* % 2)))
;; => [:ok 12]

;; Symbolic (same result)
(-> (r/ok 5)
    (r/>> inc)
    (r/>> #(* % 2)))
;; => [:ok 12]
```

## Testing

```bash
lein test
```

## License

MIT License

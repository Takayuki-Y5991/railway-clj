(defproject org.clojars.konkon/railway-clj "0.1.0"
  :description "Railway Oriented Programming library for Clojure with async support, circuit breaker, and retry mechanisms"
  :url "https://github.com/Takayuki-Y5991/railway-clj"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [org.clojure/core.async "1.7.701"]
                 [org.clojure/tools.logging "1.3.0"]
                 [org.clojure/spec.alpha "0.3.218"]]
  :profiles {:dev {:dependencies [[org.clojure/test.check "1.1.1"]]
                              :plugins [[lein-cloverage "1.2.4"]]}}
  :cloverage {:fail-threshold 70}
  :min-lein-version "2.0.0"
  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :sign-releases false}]]
  :scm {:name "git"
        :url "https://github.com/Takayuki-Y5991/railway-clj"})
(defproject railway-clj "0.1.0-SNAPSHOT"
  :description "Railway Oriented Programming library for Clojure"
  :url "https://github.com/Takayuki-Y5991/railway-clj"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [org.clojure/core.async "1.7.701"]
                 [org.clojure/tools.logging "1.3.0"]
                 [org.clojure/spec.alpha "0.3.218"]]
  :profiles {:dev {:dependencies [[org.clojure/test.check "1.1.1"]]}})
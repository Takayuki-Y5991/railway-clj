(defproject org.clojars.konkon/railway-clj "0.2.0"
  :description "Minimal Railway Oriented Programming for Clojure"
  :url "https://github.com/Takayuki-Y5991/railway-clj"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.12.0"]]
  :profiles {:dev {:dependencies [[org.clojure/test.check "1.1.1"]]
                   :plugins [[lein-cloverage "1.2.4"]]}}
  :cloverage {:fail-threshold 90}
  :min-lein-version "2.0.0"
  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :sign-releases false}]]
  :aliases {"publish" ["deploy" "clojars"]}
  :scm {:name "git"
        :url "https://github.com/Takayuki-Y5991/railway-clj"})

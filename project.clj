(defproject skyscraper "0.3.1-SNAPSHOT"
  :description "Structural scraping for the rest of us."
  :license {:name "MIT", :url "https://github.com/nathell/skyscraper/blob/master/README.md#license"}
  :scm {:name "git", :url "https://github.com/nathell/skyscraper"}
  :codox {:metadata {:doc/format :markdown}}
  :url "https://github.com/nathell/skyscraper"
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/core.async "1.5.648"]
                 [org.clojure/core.incubator "0.1.4"]
                 [org.clojure/data.csv "1.0.1"]
                 [org.clojure/data.priority-map "1.1.0"]
                 [org.clojure/java.jdbc "0.7.12"]
                 [clj-http "3.12.3"]
                 [crouton "0.1.2"]
                 [enlive "1.1.6" :exclusions [jsoup]]
                 [reaver "0.1.3"]
                 [com.taoensso/timbre "5.2.1"]
                 [org.xerial/sqlite-jdbc "3.36.0.3"]]
  :profiles {:test {:dependencies [[hiccup "1.0.5"]
                                   [ring "1.9.5"]]}})

(defproject skyscraper "0.3.0-SNAPSHOT"
  :description "Structural scraping for the rest of us."
  :license {:name "MIT", :url "https://github.com/nathell/skyscraper/blob/master/README.md#license"}
  :scm {:name "git", :url "https://github.com/nathell/skyscraper"}
  :codox {:metadata {:doc/format :markdown}}
  :url "https://github.com/nathell/skyscraper"
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/core.async "0.7.559"]
                 [org.clojure/core.incubator "0.1.4"]
                 [org.clojure/data.csv "0.1.4"]
                 [org.clojure/data.priority-map "0.0.10"]
                 [org.clojure/java.jdbc "0.7.11"]
                 [clj-http "3.10.0"]
                 [crouton "0.1.2"]
                 [enlive "1.1.6" :exclusions [jsoup]]
                 [http-kit "2.3.0"]
                 [reaver "0.1.2"]
                 [com.taoensso/timbre "4.10.0"]
                 [org.xerial/sqlite-jdbc "3.30.1"]]
  :profiles {:test {:dependencies [[hiccup "1.0.5"]
                                   [ring "1.8.0"]]}})

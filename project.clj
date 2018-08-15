(defproject skyscraper "0.3.0-SNAPSHOT"
  :description "Structural scraping for the rest of us."
  :license {:name "MIT", :url "https://github.com/nathell/skyscraper/blob/master/README.md#license"}
  :scm {:name "git", :url "https://github.com/nathell/skyscraper"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/core.async "0.4.474"]
                 [org.clojure/core.incubator "0.1.4"]
                 [org.clojure/data.csv "0.1.4"]
                 [org.clojure/java.jdbc "0.7.6"]
                 [clj-http "3.9.1"]
                 [http-kit "2.3.0"]
                 [reaver "0.1.2"]
                 [com.taoensso/timbre "4.10.0"]
                 [org.xerial/sqlite-jdbc "3.21.0.1"]])

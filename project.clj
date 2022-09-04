(defproject skyscraper "0.3.3"
  :description "Structural scraping for the rest of us."
  :license {:name "MIT", :url "https://github.com/nathell/skyscraper/blob/master/README.md#license"}
  :scm {:name "git", :url "https://github.com/nathell/skyscraper"}
  :codox {:metadata {:doc/format :markdown}}
  :url "https://github.com/nathell/skyscraper"
  :plugins [[lein-tools-deps "0.4.5"]]
  :middleware [lein-tools-deps.plugin/resolve-dependencies-with-deps-edn]
  :lein-tools-deps/config {})

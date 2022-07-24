(ns skyscraper.examples.tree-species
  (:require [reaver]
            [skyscraper.core :as core :refer [defprocessor]]))

(def seed [{:url "https://www.forestresearch.gov.uk/tools-and-resources/tree-species-database"
            :processor :tree-list
            :page 1}])

(defprocessor :tree-list
  :cache-template "tree-species/list/page/:page"
  :skyscraper.db/columns [:english-name :latin-name]
  :skyscraper.db/key-columns [:english-name]
  :process-fn (fn [doc ctx]
                (concat
                 (reaver/extract-from doc "#tree-listing > div"
                                      [:english-name :latin-name :url :processor]
                                      "h3" reaver/text
                                      "i" reaver/text
                                      "a" (reaver/attr :href)
                                      "a" (constantly :tree))
                 (when-let [next-page-url (-> (reaver/select doc ".forestry__pagination-last:not(.disabled) a") first (reaver/attr :href))]
                   [{:url next-page-url
                     :processor :tree-list
                     :page (inc (:page ctx))}]))))

(defprocessor :tree
  :cache-template "tree-species/tree/:english-name"
  :skyscraper.db/columns [:description-html]
  :process-fn (fn [doc ctx]
                {:description-html (when-let [description (reaver/select doc ".forestry-body > .container > .row > .col-8")]
                                     (.html description))}))

(defn run []
  (core/scrape! seed
                :html-cache true
                :db-file "/tmp/trees.db"
                :parse-fn core/parse-reaver))

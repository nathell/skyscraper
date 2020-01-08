(ns skyscraper.examples.tree-species
  (:require [reaver]
            [skyscraper.core :as core :refer [defprocessor]]))

(def seed [{:url "https://www.forestresearch.gov.uk/tools-and-resources/tree-species-database"
            :processor :tree-list
            :page 1}])

(defprocessor :tree-list
  :cache-template "tree-species/list/page/:page"
  :process-fn (fn [doc ctx]
                (concat
                 (reaver/extract-from doc ".listing--trees .listing-item"
                                      [:english-name :latin-name :url :processor]
                                      ".listing__heading" reaver/text
                                      ".listing__metadata" reaver/text
                                      ".listing-item" (reaver/attr :href)
                                      ".listing-item" (constantly :tree))
                 (when-let [next-page-url (-> (reaver/select doc ".pagination__item--next") first (reaver/attr :href))]
                   [{:url next-page-url
                     :processor :tree-list
                     :page (inc (:page ctx))}]))))

(defprocessor :tree
  :cache-template "tree-species/tree/:english-name"
  :process-fn (fn [doc ctx]
                {:description-html (when-let [description (reaver/select doc ".is-typeset--article")]
                                     (.html description))}))

(defn run []
  (core/scrape seed
               :html-cache true
               :parse-fn core/reaver-parse))

;; This example extracts forest data from the UK Forestry Commission site.

(ns skyscraper.forests
  (:require [skyscraper :refer :all]
            [net.cgrand.enlive-html :as enlive :refer [select attr? attr= text emit* has pred first-child last-child nth-child root]]))

(defn seed [& _]
  [{:url "http://www.forestry.gov.uk/website/forestry.nsf/SearchResults?Open&searchagent=Place&woodname=&nt=&cnty=All%20Counties",
    :processor :pages}])

(defprocessor pages
  :cache-template "forests/index"
  :process-fn (fn [res]
                (for [a (select res [[:div (attr= :style "float:right;")] :a])]
                  {:page (text a),
                   :url (href a),
                   :processor :page})))

(defprocessor page
  :cache-template "forests/:page"
  :process-fn (fn [res]
                (for [a (select res [[:div (attr= :style "clear:both")] :div :div.vah2 :a])]
                  {:forest (text a),
                   :url (href a),
                   :processor :forest})))

(defprocessor forest
  :cache-template "forests/:page/:forest"
  :process-fn (fn [res]
                (let [divs (select res [[:div (has [[root :span (attr= :style "font-weight:bold")]])]])
                      m (apply hash-map (select divs [enlive/text-node]))]
                  [{:description (-> (select res [[:meta (attr= :name "description")]]) first :attrs :content),
                    :postcode (m "Postcode: "),
                    :os-grid-ref (m "OS Grid ref: ")}])))

(ns skyscraper.enlive-helpers
  "Utility functions for use in Enlive-based scrapers."
  (:require
    [net.cgrand.enlive-html :as enlive]))

(defn href
  "Returns the href of an `<a>` node, potentially wrapped in
  another node."
  [x]
  (cond
    (nil? x) nil
    (and (map? x) (= :a (:tag x))) (-> x :attrs :href)
    :otherwise (href (first (enlive/select x [:a])))))

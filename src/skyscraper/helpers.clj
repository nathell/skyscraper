(ns skyscraper.helpers
  (:require
    [net.cgrand.enlive-html :as enlive]))

(defn href
  [x]
  (cond
   (nil? x) nil
   (and (map? x) (= :a (:tag x))) (-> x :attrs :href)
   :otherwise (href (first (enlive/select x [:a])))))

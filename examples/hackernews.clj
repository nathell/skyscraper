;; This example is incomplete and will be completed later on.

(ns skyscraper.hackernews
  (:require
    [net.cgrand.enlive-html :refer [select attr? text emit* has pred first-child last-child nth-child]]
    [skyscraper :refer :all]
    [skyscraper.helpers :refer [href]]))

(defn seed [& _]
  [{:url "https://news.ycombinator.com/",
    :processor :stories}])

(defn extract-number [item]
  (when-let [s (re-find #"\d+" (text item))]
     (Long/parseLong s)))

(defprocessor :stories
  :cache-template "hn/index"
  :process-fn (fn [res context]
                (let [rows (select res [:table :> [:tr (nth-child 3)] :table :tr])]
                  (for [[title-row author-row _] (partition 3 (drop-last 2 rows))
                        :let [a-title (first (select title-row [:td.title :a]))
                              a-author (first (select author-row [[:a (nth-child 2)]]))]]
                    {:story-url (href a-title),
                     :title (text a-title),
                     :score (extract-number (first (select author-row [:span.score])))
                     :author (when (= (-> author-row :content second :content (nth 2)) " by ")
                               (text a-author)),
                     :time (text (first (select author-row [:span.age :a])))
                     :num-comments (extract-number (text (first (select author-row [:td :> [:a last-child]])))),
                     :url (href (select author-row [:span.age :a]))
                     :id (extract-number (href (select author-row [:span.age :a])))
                     :processor :comments}))))

(defprocessor :comments
  :cache-template "hn/story/:id"
  :process-fn (fn [res context]
                {:commenters (mapv text (select res [:a.hnuser]))}))

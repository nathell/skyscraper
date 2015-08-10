(ns skyscraper.hackernews
  (:require [skyscraper :refer :all]
            [net.cgrand.enlive-html :refer [select attr? text emit* has pred first-child last-child nth-child]]))

(defn seed [& _]
  [{:url "https://news.ycombinator.com/",
    :processor :stories}])

(defprocessor stories
  :cache-template "hn/index"
  :process-fn (fn [res]
                (let [rows (select res [:table :> [:tr (nth-child 3)] :table :tr])]
                  (for [[title-row author-row _] (partition 3 (drop-last 2 rows))
                        :let [a-title (first (select title-row [:td.title :a]))
                              a-author (first (select author-row [[:a (nth-child 2)]]))]]
                    [{:story-url (href a-title),
                      :title (text a-title), 
                      :score (text (first (select author-row [:span.score]))),
                      :author (text a-author),
                      :time (text (first (select author-row [[:a (nth-child 3)]])))
                      :comments (text (first (select author-row [[:a (nth-child 4)]])))}
                     {:url (href a-author),
                      :processor :author}
                     {:url (href a-title),
                      :processor :title}]))))

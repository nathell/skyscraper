(ns skyscraper.db-self-pointing-test
  (:require
    [clojure.java.jdbc :as jdbc]
    [clojure.test :as test :refer [deftest is]]
    [net.cgrand.enlive-html :refer [select text]]
    [skyscraper.cache :as cache]
    [skyscraper.core :refer [defprocessor scrape scrape!]]
    [skyscraper.enlive-helpers :refer [href]]
    [skyscraper.test-utils :refer [make-seed resp-page with-server with-temporary-sqlite-db]]
    [taoensso.timbre :as timbre]))

(defn items [start end]
  [:ul
   (for [i (range start (inc end))]
     [:li "Item " i])])

(defn handler [{:keys [uri]}]
  (condp = uri
    "/" (resp-page [:a {:href "/page2"} "Page 2"]
                   (items 1 3))
    "/page2" (resp-page (items 4 5))
    {:status 404}))

(defprocessor ::items
  :skyscraper.db/columns [:item]
  :skyscraper.db/key-columns [:item]
  :process-fn (fn [res ctx]
                (concat
                 (for [a (select res [:a])]
                  {:url (href a),
                   :processor ::items})
                 (for [li (select res [:li])]
                   {:item (text li)}))))

(deftest db-self-pointing-test
  (timbre/set-level! :info)
  (dotimes [_ 50]
    (with-server handler
      (with-temporary-sqlite-db conn
        (scrape! (make-seed ::items)
                 :db (:connection-uri conn))
        (is (= (jdbc/query conn "SELECT count(*) cnt FROM items")
               [{:cnt 6}]))
        (is (= (jdbc/query conn "SELECT item FROM items WHERE parent IS NULL ORDER BY item")
               [{:item nil} {:item "Item 1"} {:item "Item 2"} {:item "Item 3"}]))
        (is (= (jdbc/query conn "SELECT item FROM items WHERE parent IS NOT NULL ORDER BY item")
               [{:item "Item 4"} {:item "Item 5"}]))))))

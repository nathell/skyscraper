(ns skyscraper.nondistinct-test
  (:require
    [clojure.test :as test :refer [deftest is]]
    [net.cgrand.enlive-html :refer [select text]]
    [skyscraper.cache :as cache]
    [skyscraper.core :refer [defprocessor scrape scrape!]]
    [skyscraper.enlive-helpers :refer [href]]
    [skyscraper.test-utils :refer [make-seed resp-page with-server]]
    [taoensso.timbre :as timbre]))

(defn handler [{:keys [uri]}]
  (condp = uri
    "/" (resp-page (repeat 100 [:p [:a {:href "/second"} "Next"]]))
    "/second" (resp-page [:p "Item"])
    {:status 404}))

(defprocessor ::root
  :process-fn (fn [res ctx]
                (for [a (select res [:a])]
                  {:url (href a),
                   :processor ::second})))

(defprocessor ::second
  :process-fn (fn [res ctx]
                (for [p (select res [:p])]
                  {:item (text p)})))

(deftest nondistinct-test
  (timbre/set-level! :warn)
  (let [seed (make-seed ::root)]
    (with-server handler
      (is (= (scrape seed)
             [{:item "Item"}])))))

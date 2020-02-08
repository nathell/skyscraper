(ns skyscraper.updates-test
  (:require
    [clojure.test :as test :refer [deftest is]]
    [net.cgrand.enlive-html :refer [select text]]
    [skyscraper.cache :as cache]
    [skyscraper.core :refer [defprocessor scrape]]
    [skyscraper.enlive-helpers :refer [href]]
    [skyscraper.test-utils :refer [make-seed resp-page with-server]]
    [taoensso.timbre :as timbre]))

(def visited-pages (atom #{}))

(defn clean-visited-pages! []
  (reset! visited-pages #{}))

(defn make-handler [max-pages]
  (fn handler [{:keys [uri]}]
    (if (= uri "/")
      (resp-page [:ul (for [x (range 1 (inc max-pages))]
                        [:li [:a {:href (str "/" x)} "Number " x]])])
      (let [number (Long/parseLong (subs uri 1))]
        (swap! visited-pages conj number)
        (resp-page [:h1 number])))))

(defprocessor ::start
  :cache-template "index"
  :updatable true
  :process-fn (fn [res ctx]
                (for [[i link] (map-indexed vector (select res [:a]))]
                  {:index i, :url (href link), :processor ::num-page})))

(defprocessor ::num-page
  :cache-template "number/:index"
  :process-fn (fn [res ctx]
                {:number (Long/parseLong (text (first (select res [:h1]))))}))

(deftest updates-test
  (timbre/set-level! :info)
  (let [cache (cache/memory)]
    (clean-visited-pages!)
    (with-server (make-handler 10)
      (let [results (doall (scrape (make-seed ::start)
                                   :html-cache cache))]
        (is (= (set (map :number results)) (set (range 1 11))))
        (is (= (set @visited-pages) (set (range 1 11))))))
    (clean-visited-pages!)
    (with-server (make-handler 20)
      (let [results (doall (scrape (make-seed ::start)
                                   :html-cache cache
                                   :update true))]
        (is (= (set (map :number results)) (set (range 1 21))))
        (is (= (set @visited-pages) (set (range 11 21))))))
    (clean-visited-pages!)
    (with-server (make-handler 30)
      (let [results (doall (scrape (make-seed ::start)
                                   :html-cache cache
                                   :update true
                                   :uncached-only true))]
        (is (= (set (map :number results)) (set (range 21 31))))
        (is (= (set @visited-pages) (set (range 21 31))))))))

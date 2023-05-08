(ns skyscraper.updates-test
  (:require
    [clojure.test :as test :refer [deftest is]]
    [net.cgrand.enlive-html :refer [select text]]
    [skyscraper.cache :as cache]
    [skyscraper.core :refer [defprocessor cached-document scrape]]
    [skyscraper.enlive-helpers :refer [href]]
    [skyscraper.test-utils :refer [make-seed resp-page with-server]]
    [taoensso.timbre :as timbre]))

(def visited-pages (atom #{}))

(defn clean-visited-pages! []
  (reset! visited-pages #{}))

(defn make-handler [max-pages footprint]
  (fn handler [{:keys [uri]}]
    (if (= uri "/")
      (resp-page [:h1 footprint]
                 [:ul (for [x (range 1 (inc max-pages))]
                        [:li [:a {:class footprint
                                  :href (str "/" x)} "Number " x]])])
      (let [number (Long/parseLong (subs uri 1))]
        (swap! visited-pages conj number)
        (resp-page [:h1 number])))))

(defprocessor ::start
  :cache-template "index"
  :updatable true
  :process-fn (fn [res ctx]
                (let [footprint (text (first (select res [:h1])))
                      old-footprint (when-let [res' (cached-document ctx)]
                                      (text (first (select res' [:h1]))))]
                  (let [res (for [[i link] (map-indexed vector (select res [:a]))]
                              {:index i, :url (href link), :processor ::num-page, :update? (and old-footprint (not= footprint old-footprint))})]
                    res))))

(defprocessor ::num-page
  :cache-template "number/:index"
  :updatable :update?
  :process-fn (fn [res ctx]
                {:number (Long/parseLong (text (first (select res [:h1]))))}))

(deftest updates-test
  (timbre/set-level! :info)
  (doseq [cache-option [:html-cache :processed-cache]]
    (let [cache (cache/memory)
          uscrape #(doall (apply scrape (make-seed ::start)
                                 (into [cache-option cache] %&)))]
      (clean-visited-pages!)
      (with-server (make-handler 10 "a")
        (let [results (uscrape)]
          (is (= (set (map :number results)) (set (range 1 11))))
          (is (= (set @visited-pages) (set (range 1 11))))))
      (clean-visited-pages!)
      (with-server (make-handler 20 "a")
        (let [results (uscrape :update true)]
          (is (= (set (map :number results)) (set (range 1 21))))
          (is (= (set @visited-pages) (set (range 11 21))))))
      (clean-visited-pages!)
      (with-server (make-handler 30 "a")
        (let [results (uscrape :update true
                               :uncached-only true)]
          (is (= (set (map :number results)) (set (range 21 31))))
          (is (= (set @visited-pages) (set (range 21 31))))))
      (clean-visited-pages!)
      ;; this test only makes sense when processed-cache is not enabled, as
      ;; otherwise cached-document is circumvented
      (when (= cache-option :html-cache)
        (with-server (make-handler 30 "b")
          (let [results (uscrape :update true)]
            (is (= (set (map :number results)) (set (range 1 31))))
            (is (= (set @visited-pages) (set (range 1 31))))))))))

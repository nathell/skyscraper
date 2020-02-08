(ns skyscraper.character-encoding-test
  (:require
    [clojure.test :as test :refer [deftest is]]
    [net.cgrand.enlive-html :refer [select text]]
    [skyscraper.cache :as cache]
    [skyscraper.core :refer [defprocessor scrape scrape!]]
    [skyscraper.enlive-helpers :refer [href]]
    [skyscraper.test-utils :refer [make-seed resp-page with-server]]
    [taoensso.timbre :as timbre]))

(def polish-text "Filmuj rzeź żądań, pość, gnęb chłystków")
(def polish-html-latin2 (.getBytes (str "<html><body><p>" polish-text "</p></body></html>") "ISO-8859-2"))

(defn handler [{:keys [uri]}]
  {:status 200,
   :headers {"Content-Type" "text/html; charset=ISO-8859-2"},
   :body polish-html-latin2})

(defprocessor ::root
  :cache-template "character-encoding/index"
  :process-fn (fn [res ctx]
                {:text (text (first (select res [:p])))}))

;; Like MemoryCache, but doesn't preserve the type of metadata it
;; stores (neither does FSCache).

(deftype CoerciveMemoryCache
    [storage]
  cache/CacheBackend
  (save-blob [cache key blob metadata]
    (swap! storage assoc key {:blob blob, :meta (into {} metadata)}))
  (load-blob [cache key]
    (@storage key)))

(deftest character-encoding-test
  (timbre/set-level! :info)
  (let [cache (CoerciveMemoryCache. (atom {}))]
    (with-server handler
      (scrape! (make-seed ::root)
               :html-cache cache)
      (is (= (scrape (make-seed ::root)
                     :html-cache cache)
             [{:text polish-text}])))))

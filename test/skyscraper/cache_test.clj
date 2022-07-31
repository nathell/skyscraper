(ns skyscraper.cache-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [net.cgrand.enlive-html :refer [select text]]
            [skyscraper.cache :as cache]
            [skyscraper.core :refer :all]
            [skyscraper.enlive-helpers :refer [href]]
            [skyscraper.test-utils :refer :all]
            [taoensso.timbre :as timbre]))

(defn close-aware-cache
  [closed?]
  (let [ensure-not-closed! #(when @closed?
                              (throw (Exception. "cache already closed")))]
    (reify
      cache/CacheBackend
      (save-blob [cache key blob metadata]
        (ensure-not-closed!)
        nil)
      (load-blob [cache key]
        (ensure-not-closed!)
        nil)
      java.io.Closeable
      (close [cache]
        (ensure-not-closed!)
        (reset! closed? true)))))

(defn handler [{:keys [uri]}]
  (condp = uri
    "/" (resp-page [:h1 "Hello world"])
    "/parent" (resp-page [:a {:href "child"} "Next"])
    "/child" (resp-page [:h1 "Text"])))

(defprocessor ::start
  :cache-template "index"
  :process-fn (fn [res ctx]
                (for [x (select res [:h1])]
                  {:text (text x)})))

(defprocessor ::parent
  ;; no cache-template
  :process-fn (fn [res ctx]
                (for [x (select res [:a])]
                  {:url (href x), :processor ::child})))

(defprocessor ::child
  :cache-template "child"
  :process-fn (fn [res ctx]
                (for [x (select res [:h1])]
                  {:text (text x)})))

(deftest test-closing-cache
  (let [closed? (atom false)
        cache (close-aware-cache closed?)
        seed (make-seed ::start)]
    (with-server handler
      (testing "scraping should close the cache"
        (is (= (scrape seed :html-cache cache) [{:text "Hello world"}]))
        (is @closed?))
      (testing "subsequent scraping should throw an exception"
        (is (thrown? Exception (doall (scrape seed :html-cache cache))))))))

(defn test-fs-cache []
  (cache/fs (str (java.nio.file.Files/createTempDirectory "html-cache" (into-array java.nio.file.attribute.FileAttribute [])))))

(deftest test-processed-cache-with-missing-keys
  (with-server handler
    (testing "scrape works correctly"
      (is (= (scrape (make-seed ::parent "/parent")
                     :processed-cache (test-fs-cache))
             [{:text "Text"}])))))

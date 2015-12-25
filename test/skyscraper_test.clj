(ns skyscraper-test
  (:require [clojure.test :refer :all]
            [clj-http.client :as http]
            [clojure.string :as string]
            [skyscraper :refer :all]
            [skyscraper.cache :as cache]
            [taoensso.timbre :as timbre]
            [net.cgrand.enlive-html :refer [select text]]))

(defn dummy-site-content
  [i]
  (format "<html><h1>Number %s</h1>%s</html>"
          i
          (if (>= i 100)
            ""
            (apply str (for [n (range 10) :let [m (+ n (* 10 i))] :when (pos? m)] (format "<a href='/%s'>Page %s</a>" m m))))))

(defn url-number
  [url]
  (Integer/parseInt (last (string/split url #"/"))))

(def hits (atom 0))

(defn mock-get
  [url & args]
  (swap! hits inc)
  {:body (dummy-site-content (url-number url))})

(defprocessor root
  :cache-key-fn (fn [ctx] (str "numbers/" (url-number (:url ctx))))
  :process-fn (fn [res ctx]
                (let [num (text (first (select res [:h1])))
                      subpages (select res [:a])]
                  (if (seq subpages)
                    (for [a subpages]
                      {:processor :root, :url (href a)})
                    {:number num}))))

(defn seed [& _]
  [{:url "http://localhost/0", :processor :root}])

(timbre/set-level! :warn)

(deftest basic-scraping
  (with-redefs [http/get mock-get]
    (is (= (count (scrape :skyscraper-test/seed :html-cache nil :processed-cache nil))
           900))))

(deftest caches
  (reset! hits 0)
  (with-redefs [http/get mock-get]
    (let [cache (cache/memory)]
      (is (= (count (scrape :skyscraper-test/seed :html-cache cache :processed-cache cache)) 900))
      (let [hits-before @hits
            _ (dorun (scrape :skyscraper-test/seed :html-cache cache :processed-cache cache))
            hits-after @hits]
        (is (= hits-before hits-after)))
      (let [res1 (doall (scrape :skyscraper-test/seed :html-cache cache :processed-cache cache))
            res2 (doall (scrape :skyscraper-test/seed :html-cache cache :processed-cache nil))
            res3 (doall (scrape :skyscraper-test/seed :html-cache nil :processed-cache cache))
            res4 (doall (scrape :skyscraper-test/seed :html-cache nil :processed-cache nil))]
        (is (= res1 res2 res3 res4))))))

(deftest test-merge-urls
  (are [y z] (= (merge-urls "https://foo.pl/bar/baz" y) z)
       "http://bar.uk/baz/foo" "http://bar.uk/baz/foo"
       "//bar.uk/baz/foo" "https://bar.uk/baz/foo"
       "/baz/foo" "https://foo.pl/baz/foo"
       "foo" "https://foo.pl/bar/foo"))

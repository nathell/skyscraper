(ns skyscraper.real-test
  (:require
    [clojure.java.jdbc :as jdbc]
    [clojure.string :as string]
    [clojure.test :as test :refer [deftest is]]
    [hiccup.page :refer [html5]]
    [net.cgrand.enlive-html :refer [select text]]
    [ring.adapter.jetty :refer [run-jetty]]
    [ring.middleware.cookies :refer [wrap-cookies]]
    [ring.util.response :as response]
    [skyscraper.cache :as cache]
    [skyscraper.core :refer :all]
    [skyscraper.enlive-helpers :refer [href]]
    [skyscraper.test-utils :refer [make-seed resp-page with-server with-temporary-sqlite-db]]
    [taoensso.timbre :as timbre]))

;; basic-cookie-test

(defn basic-cookie-test-handler [{:keys [cookies uri]}]
  (condp = uri
    "/" {:status 200,
         :headers {"Set-Cookie" "secret=donttellanyone"},
         :body (html5 [:a {:href "/secret"} "Got a cookie?"])}
    "/secret" (resp-page
               [:p (if (= (get-in cookies ["secret" :value]) "donttellanyone")
                     "You got it!"
                     "You ain't got it")])
    {:status 404}))

(defprocessor :basic-cookie-test-root
  :process-fn (fn [res ctx]
                (for [link (select res [:a])]
                  {:link-text (text link), :url (href link), :processor :basic-cookie-test-secret})))

(defprocessor :basic-cookie-test-secret
  :process-fn (fn [res ctx]
                (for [item (select res [:p])]
                  {:target (text item)})))

(deftest basic-cookie-test
  (timbre/set-level! :warn)
  (let [seed (make-seed :basic-cookie-test-root)]
    (with-server (wrap-cookies basic-cookie-test-handler)
      (is (= (scrape seed)
             [{:link-text "Got a cookie?", :target "You got it!"}])))))

;; http-method-reset-test

(defn http-method-reset-test-handler [{:keys [cookies uri request-method] :as req}]
  (condp = [request-method uri]
    [:get "/"] (resp-page [:form {:method "post" :action "/second"}
                           [:button "Submit"]])
    [:post "/second"] (resp-page [:a {:href "/third"} "Next"])
    [:get "/third"] (resp-page [:p "Success"])
    {:status 404}))

(defprocessor :http-method-reset-test-root
  :process-fn (fn [res ctx]
                (for [{:keys [attrs] :as form} (select res [:form])]
                  {:url (:action attrs),
                   :http/method (keyword (:method attrs)),
                   :processor :http-method-reset-test-second})))

(defprocessor :http-method-reset-test-second
  :process-fn (fn [res ctx]
                (for [link (select res [:a])]
                  {:url (href link),
                   :processor :http-method-reset-test-third})))

(defprocessor :http-method-reset-test-third
  :process-fn (fn [res ctx]
                (for [p (select res [:p])]
                  {:output (text p)})))

(deftest http-method-reset-test
  (timbre/set-level! :warn)
  (with-server http-method-reset-test-handler
    (is (= (scrape (make-seed :http-method-reset-test-root))
           [{:output "Success"}]))))

;; nondistinct-test

(defn nondistinct-test-handler [{:keys [uri]}]
  (condp = uri
    "/" (resp-page (repeat 100 [:p [:a {:href "/second"} "Next"]]))
    "/second" (resp-page [:p "Item"])
    {:status 404}))

(defprocessor :nondistinct-test-root
  :process-fn (fn [res ctx]
                (for [a (select res [:a])]
                  {:url (href a),
                   :processor :nondistinct-test-second})))

(defprocessor :nondistinct-test-second
  :process-fn (fn [res ctx]
                (for [p (select res [:p])]
                  {:item (text p)})))

(deftest nondistinct-test
  (timbre/set-level! :warn)
  (let [seed (make-seed :nondistinct-test-root)]
    (with-server nondistinct-test-handler
      (is (= (scrape seed)
             [{:item "Item"}])))))

;; db-self-pointing-test

(defn db-self-pointing-test-items [start end]
  [:ul
   (for [i (range start (inc end))]
     [:li "Item " i])])

(defn db-self-pointing-test-handler [{:keys [uri]}]
  (condp = uri
    "/" (resp-page [:a {:href "/page2"} "Page 2"]
                   (db-self-pointing-test-items 1 3))
    "/page2" (resp-page (db-self-pointing-test-items 4 5))
    {:status 404}))

(defprocessor :db-self-pointing-test-items
  :skyscraper.db/columns [:item]
  :skyscraper.db/key-columns [:item]
  :process-fn (fn [res ctx]
                (concat
                 (for [a (select res [:a])]
                  {:url (href a),
                   :processor :db-self-pointing-test-items})
                 (for [li (select res [:li])]
                   {:item (text li)}))))

(deftest db-self-pointing-test
  (dotimes [_ 50]
    (with-server db-self-pointing-test-handler
      (with-temporary-sqlite-db conn
        (scrape! (make-seed :db-self-pointing-test-items)
                 :db (:connection-uri conn))
        (is (= (jdbc/query conn "SELECT count(*) cnt FROM db_self_pointing_test_items")
               [{:cnt 6}]))
        (is (= (jdbc/query conn "SELECT item FROM db_self_pointing_test_items WHERE parent IS NULL ORDER BY item")
               [{:item nil} {:item "Item 1"} {:item "Item 2"} {:item "Item 3"}]))
        (is (= (jdbc/query conn "SELECT item FROM db_self_pointing_test_items WHERE parent IS NOT NULL ORDER BY item")
               [{:item "Item 4"} {:item "Item 5"}]))))))

;; character-encoding-test

(def polish-text "Filmuj rzeź żądań, pość, gnęb chłystków")
(def polish-html-latin2 (.getBytes (str "<html><body><p>" polish-text "</p></body></html>") "ISO-8859-2"))

(defn character-encoding-test-handler [{:keys [uri]}]
  {:status 200,
   :headers {"Content-Type" "text/html; charset=ISO-8859-2"},
   :body polish-html-latin2})

(defprocessor :character-encoding
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
  (let [cache (CoerciveMemoryCache. (atom {}))]
    (with-server character-encoding-test-handler
      (scrape! (make-seed :character-encoding)
               :html-cache cache)
      (is (= (scrape (make-seed :character-encoding)
                     :html-cache cache)
             [{:text polish-text}])))))

;; overridable-parse-fn-test

(defn overridable-parse-fn-test-handler [{:keys [uri]}]
  (condp = uri
    "/" (resp-page [:a {:href "/numbers"} "Some numbers"])
    "/numbers" (response/response "8,80,418")))

(defprocessor :overridable-parse-fn-start
  :process-fn (fn [res ctx]
                (for [link (select res [:a])]
                  {:url (href link), :processor :overridable-parse-fn-numbers})))

(defprocessor :overridable-parse-fn-numbers
  :parse-fn parse-string
  :process-fn (fn [res ctx]
                (for [x (string/split res #",")]
                  {:number (Integer/parseInt x)})))

(deftest overridable-parse-fn-test
  (with-server overridable-parse-fn-test-handler
    (is (= (scrape (make-seed :overridable-parse-fn-start))
           [{:number 8} {:number 80} {:number 418}]))))

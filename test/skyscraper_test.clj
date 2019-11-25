(ns skyscraper-test
  (:require [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as string]
            [clojure.test :refer :all]
            [net.cgrand.enlive-html :refer [select text]]
            [skyscraper :refer :all]
            [skyscraper.cache :as cache]
            [skyscraper.helpers :refer [href]]
            [taoensso.timbre :as timbre]))

(defn dummy-site-content
  [i]
  (format "<html><h1>Number %s</h1>%s</html>"
          i
          (if (>= i 100)
            ""
            (apply str (for [n (range 10) :let [m (+ n (* 10 i))] :when (pos? m)] (format "<a href='/%s'>Page %s</a>" m m))))))

(defn url-number
  [url]
  (try
    (Integer/parseInt (last (string/split url #"/")))
    (catch Exception _ 0)))

(def hits (atom 0))

(defn mock-request
  [{:keys [url async?]} & [success-fn error-fn]]
  (swap! hits inc)
  (let [response {:headers {"content-type" "text/html; charset=utf-8"}
                  :body (.getBytes (dummy-site-content (url-number url)))}]
    (if async?
      (success-fn response)
      response)))

(defn process-root [res {:keys [i] :as ctx}]
  (let [numtext (text (first (select res [:h1])))
        number (Long/parseLong (subs numtext 7))
        subpages (select res [:a])]
    (if (seq subpages)
      (for [a subpages]
        (assoc
         {:processor :root, :url (href a), :i (inc i)}
         (keyword (str "level" i)) number))
      {:number number})))

(defprocessor :root
  :cache-key-fn (fn [ctx] (str "numbers/" (url-number (:url ctx))))
  :process-fn process-root)

(defprocessor :root-uncached
  :process-fn process-root)

(defn seed [& _]
  [{:url "http://localhost/0", :i 0, :processor :root}])

(defn seed-uncached [& _]
  [{:url "http://localhost/0", :i 0, :processor :root-uncached}])

(timbre/set-level! :warn)

(deftest basic-scraping
  (is (= (count (scrape (seed)
                        :html-cache nil
                        :processed-cache nil
                        :request-fn mock-request))
         900))
  (is (= (count (scrape (seed)
                        :html-cache nil
                        :processed-cache nil
                        :request-fn mock-request
                        :download-mode :sync))
         900)))

(deftest test-only
  (reset! hits 0)
  (is (= (->> (scrape (seed)
                      :only {:level1 1}
                      :html-cache nil
                      :processed-cache nil
                      :request-fn mock-request)
              (map :number)
              (sort))
         (range 100 200))))

(deftest caches
  (reset! hits 0)
  (let [hcache (cache/memory)
        pcache (cache/memory)]
    (is (= (count (scrape (seed) :request-fn mock-request :html-cache hcache :processed-cache pcache)) 900))
    (let [hits-before @hits
          _ (dorun (scrape (seed) :request-fn mock-request :html-cache hcache :processed-cache pcache))
          hits-after @hits
          _ (dorun (scrape (seed) :request-fn mock-request :html-cache hcache :processed-cache pcache :parallelism 1 :download-mode :sync))
          hits-after-sync @hits]
      (is (= hits-before hits-after hits-after-sync)))
    (let [res1 (doall (scrape (seed) :request-fn mock-request :html-cache hcache :processed-cache pcache))
          res2 (doall (scrape (seed) :request-fn mock-request :html-cache hcache :processed-cache nil))
          res3 (doall (scrape (seed) :request-fn mock-request :html-cache nil :processed-cache pcache))
          res4 (doall (scrape (seed) :request-fn mock-request :html-cache nil :processed-cache nil))
          res5 (doall (scrape (seed-uncached) :request-fn mock-request :html-cache nil :processed-cache nil))]
      (is (apply = (mapv #(sort-by :number %) [res1 res2 res3 res4 res5]))))))

(deftest test-merge-urls
  (are [y z] (= (merge-urls "https://foo.pl/bar/baz" y) z)
       "http://bar.uk/baz/foo" "http://bar.uk/baz/foo"
       "//bar.uk/baz/foo" "https://bar.uk/baz/foo"
       "/baz/foo" "https://foo.pl/baz/foo"
       "foo" "https://foo.pl/bar/foo"))

(deftest test-allows
  (is (allows? {:k1 1, :k2 2} {:k1 1, :k3 3}))
  (is (not (allows? {:k1 1, :k2 2} {:k1 1, :k2 3})))
  (is (allows? {:k1 1} {:k1 1, :k2 2}))
  (is (allows? {} {:k1 1, :k2 2}))
  (is (allows? {:k1 1} {:k2 2})))

(defprocessor :nil-url-test-processor-root
  :cache-template "nil-url"
  :process-fn (fn [res ctx]
                (for [a (select res [:a])]
                  {:title (text a), :url (href a), :processor :nil-url-test-processor-child})))

(defprocessor :nil-url-test-processor-child
  :cache-template "nil-url/:title"
  :process-fn (fn [res ctx]
                [{:info (text (first (select res [:h1])))}]))

(deftest test-nil-url
  (let [html-main "<html><a href='/sub'>link</a><a name='anchor'>non-link</a></html>"
        html-child "<html><h1>Info</h1></html>"
        request (fn [{:keys [url]} success-fn error-fn]
                  (success-fn
                   {:headers {"content-type" "text/html; charset=utf-8"}
                    :body (.getBytes (if (string/ends-with? url "/sub") html-child html-main))}))]
    (is (= (remove :processor
                   (scrape [{:url "http://localhost/", :processor :nil-url-test-processor-root}]
                           :html-cache nil :processed-cache nil :request-fn request))
           [{:title "link", :info "Info"}]))))

(defprocessor :users
  :process-fn (fn [res ctx]
                [{:name "John", :surname "Doe", :url "/", :processor :accounts}])
  :db-columns [:name :surname]
  :id [:name :surname])

(defprocessor :accounts
  :process-fn (fn [res ctx]
                [{:bank-account "0123-4567"}
                 {:bank-account "8888-9999"}])
  :db-columns [:bank-account]
  :id [:bank-account])

(deftest test-sqlite
  (let [db-file (java.io.File/createTempFile "test" ".sqlite")
        db-uri (str "jdbc:sqlite:" db-file)]
    (try
      (dotimes [_ 2]
        (scrape! [{:url "http://example.com", :processor :users}]
                 :request-fn mock-request
                 :db-file db-file))
      (jdbc/with-db-connection [conn db-uri]
        (is (= (->> (jdbc/query conn "SELECT name, surname, bank_account FROM accounts JOIN users ON accounts.parent = users.id")
                    (sort-by :bank_account))
               [{:name "John", :surname "Doe", :bank_account "0123-4567"}
                {:name "John", :surname "Doe", :bank_account "8888-9999"}]))
        (is (= (jdbc/query conn "SELECT count(*) cnt FROM accounts")
               [{:cnt 2}])))
      (finally
        (.delete db-file)))))

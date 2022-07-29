(ns skyscraper.error-handling-test
  (:require
    [clojure.test :as test :refer [deftest is testing]]
    [net.cgrand.enlive-html :refer [select text]]
    [skyscraper.cache :as cache]
    [skyscraper.core :as core :refer [defprocessor scrape scrape!]]
    [skyscraper.enlive-helpers :refer [href]]
    [skyscraper.test-utils :refer [make-seed resp-page with-server]]
    [spy.core :as spy]
    [taoensso.timbre :as timbre]))

(def counter (atom 0))

(defn reset-counter! []
  (reset! counter 0))

(defn make-handler [error-code]
  (fn handler [{:keys [uri]}]
    (condp = uri
      "/" (resp-page [:ul (for [x (range 1 6)]
                            [:li [:a {:href (str "/" x)} "Number " x]])])
      "/5" (do (swap! counter inc)
               {:status error-code,
                :body (str "Error " error-code)})
      (let [number (Long/parseLong (subs uri 1))]
        (resp-page [:h1 number])))))

(defn make-flashing-handler []
  (let [works? (atom true)]
    (fn handler [{:keys [uri]}]
      (if (swap! works? not)
        (resp-page
         (let [number (if (= uri "/")
                        1
                        (Long/parseLong (subs uri 1)))]
           (if (= number 5)
             [:p "Leaf"]
             [:a {:href (str "/" (inc number))} "Next"])))
        {:status 500, :body "Try again!"}))))

(defn ignoring-error-handler
  [error options context]
  [])

(defprocessor ::start
  :cache-template "index"
  :process-fn (fn [res ctx]
                (for [[i link] (map-indexed vector (select res [:a]))]
                  {:index i, :url (href link), :processor ::num-page})))

(defprocessor ::num-page
  :cache-template "number/:index"
  :process-fn (fn [res ctx]
                {:number (Long/parseLong (text (first (select res [:h1]))))}))

(defprocessor ::flash
  :process-fn (fn [res ctx]
                (concat
                 (for [link (select res [:a])]
                   {:url (href link), :processor ::flash})
                 (for [p (select res [:p])]
                   {:text (text p)}))))

(deftest test-default-download-error-handler
  (timbre/set-level! :warn)
  (let [seed (make-seed ::start)]
    (testing "retries on 5xx"
      (with-server (make-handler 500)
        (reset-counter!)
        (testing "using scrape!"
          (is (thrown? Exception (scrape! seed)))
          (is (= @counter 5)))
        (reset-counter!)
        (testing "using scrape"
          (is (thrown? Exception (doall (scrape seed))))
          (is (= @counter 5)))))
    (testing "no retries on 4xx"
      (with-server (make-handler 404)
        (reset-counter!)
        (testing "using scrape!"
          (is (thrown? Exception (scrape! seed)))
          (is (= @counter 1)))
        (reset-counter!)
        (testing "using scrape"
          (is (thrown? Exception (doall (scrape seed))))
          (is (= @counter 1)))))))

(deftest test-sync-retries
  (timbre/set-level! :warn)
  (with-redefs [core/store-cache-handler (spy/spy @#'core/store-cache-handler)]
    (with-server (make-handler 500)
      (let [seed (make-seed ::start "/5")]
        (is (thrown? Exception (doall (scrape seed
                                              :download-mode :sync
                                              :parallelism 1))))
        (is (spy/not-called? @#'core/store-cache-handler))))))

(deftest test-resetting-retries
  (timbre/set-level! :warn)
  (let [seed (make-seed ::flash)]
    (with-server (make-flashing-handler)
      (let [result (scrape seed
                           :download-mode :sync
                           :parallelism 1
                           :retries 1)]
        (is (= result [{:text "Leaf"}]))))))

(deftest test-custom-download-error-handler
  (timbre/set-level! :warn)
  (let [seed (make-seed ::start)]
    (with-server (make-handler 500)
      (reset-counter!)
      (let [result (scrape seed :download-error-handler ignoring-error-handler)]
        (is (= (count result) 4))
        (is (= @counter 1))))))

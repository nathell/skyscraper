(ns skyscraper.overridable-parse-fn-test
  (:require
    [clojure.string :as string]
    [clojure.test :as test :refer [deftest is]]
    [net.cgrand.enlive-html :refer [select text]]
    [ring.util.response :as response]
    [skyscraper.cache :as cache]
    [skyscraper.core :refer [defprocessor parse-string scrape]]
    [skyscraper.enlive-helpers :refer [href]]
    [skyscraper.test-utils :refer [make-seed resp-page with-server]]
    [taoensso.timbre :as timbre]))

(defn handler [{:keys [uri]}]
  (condp = uri
    "/" (resp-page [:a {:href "/numbers"} "Some numbers"])
    "/numbers" (response/response "8,80,418")))

(defprocessor ::start
  :process-fn (fn [res ctx]
                (for [link (select res [:a])]
                  {:url (href link), :processor ::numbers})))

(defprocessor ::numbers
  :parse-fn parse-string
  :process-fn (fn [res ctx]
                (for [x (string/split res #",")]
                  {:number (Integer/parseInt x)})))

(deftest overridable-parse-fn-test
  (timbre/set-level! :info)
  (with-server handler
    (is (= (scrape (make-seed ::start))
           [{:number 8} {:number 80} {:number 418}]))))

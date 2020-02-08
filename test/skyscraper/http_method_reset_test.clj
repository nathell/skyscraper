(ns skyscraper.http-method-reset-test
  (:require
    [clojure.test :as test :refer [deftest is]]
    [net.cgrand.enlive-html :refer [select text]]
    [skyscraper.cache :as cache]
    [skyscraper.core :refer [defprocessor scrape scrape!]]
    [skyscraper.enlive-helpers :refer [href]]
    [skyscraper.test-utils :refer [make-seed resp-page with-server]]
    [taoensso.timbre :as timbre]))

(defn handler [{:keys [cookies uri request-method] :as req}]
  (condp = [request-method uri]
    [:get "/"] (resp-page [:form {:method "post" :action "/second"}
                           [:button "Submit"]])
    [:post "/second"] (resp-page [:a {:href "/third"} "Next"])
    [:get "/third"] (resp-page [:p "Success"])
    {:status 404}))

(defprocessor ::root
  :process-fn (fn [res ctx]
                (for [{:keys [attrs] :as form} (select res [:form])]
                  {:url (:action attrs),
                   :http/method (keyword (:method attrs)),
                   :processor ::second})))

(defprocessor ::second
  :process-fn (fn [res ctx]
                (for [link (select res [:a])]
                  {:url (href link),
                   :processor ::third})))

(defprocessor ::third
  :process-fn (fn [res ctx]
                (for [p (select res [:p])]
                  {:output (text p)})))

(deftest http-method-reset-test
  (timbre/set-level! :warn)
  (with-server handler
    (is (= (scrape (make-seed ::root))
           [{:output "Success"}]))))

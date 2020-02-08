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

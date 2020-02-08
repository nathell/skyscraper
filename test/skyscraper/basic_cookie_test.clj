(ns skyscraper.basic-cookie-test
  (:require
    [clojure.test :as test :refer [deftest is]]
    [hiccup.page :refer [html5]]
    [net.cgrand.enlive-html :refer [select text]]
    [ring.middleware.cookies :refer [wrap-cookies]]
    [skyscraper.cache :as cache]
    [skyscraper.core :refer [defprocessor scrape scrape!]]
    [skyscraper.enlive-helpers :refer [href]]
    [skyscraper.test-utils :refer [make-seed resp-page with-server]]
    [taoensso.timbre :as timbre]))

(defn handler [{:keys [cookies uri]}]
  (condp = uri
    "/" {:status 200,
         :headers {"Set-Cookie" "secret=donttellanyone"},
         :body (html5 [:a {:href "/secret"} "Got a cookie?"])}
    "/secret" (resp-page
               [:p (if (= (get-in cookies ["secret" :value]) "donttellanyone")
                     "You got it!"
                     "You ain't got it")])
    {:status 404}))

(defprocessor ::root
  :process-fn (fn [res ctx]
                (for [link (select res [:a])]
                  {:link-text (text link), :url (href link), :processor ::secret})))

(defprocessor ::secret
  :process-fn (fn [res ctx]
                (for [item (select res [:p])]
                  {:target (text item)})))

(deftest basic-cookie-test
  (timbre/set-level! :warn)
  (let [seed (make-seed ::root)]
    (with-server (wrap-cookies handler)
      (is (= (scrape seed)
             [{:link-text "Got a cookie?", :target "You got it!"}])))))

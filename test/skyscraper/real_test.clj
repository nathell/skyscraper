(ns skyscraper.real-test
  (:require
    [clojure.test :as test :refer [deftest is]]
    [hiccup.page :refer [html5]]
    [net.cgrand.enlive-html :refer [select text]]
    [ring.adapter.jetty :refer [run-jetty]]
    [ring.middleware.cookies :refer [wrap-cookies]]
    [ring.util.response :as response]
    [skyscraper :refer :all]
    [skyscraper.helpers :refer [href]]
    [taoensso.timbre :as timbre]))

(def port 64738) ;; let's hope it's not used

(defn make-seed
  ([processor] (make-seed processor "/"))
  ([processor initial-url] [{:processor processor
                             :url (str "http://localhost:" port initial-url)}]))

(defmacro with-server [handler & body]
  `(let [server# (run-jetty ~handler {:port port, :join? false})]
     (try
       ~@body
       (catch Throwable t#
         (.stop server#)
         (throw t#)))))

(defn basic-cookie-test-handler [{:keys [cookies uri]}]
  (condp = uri
    "/" {:status 200,
         :headers {"Set-Cookie" "secret=donttellanyone"},
         :body (html5 [:a {:href "/secret"} "Got a cookie?"])}
    "/secret" (response/response
               (html5 [:p (if (= (get-in cookies ["secret" :value]) "donttellanyone")
                            "You got it!"
                            "You ain't got it")]))
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

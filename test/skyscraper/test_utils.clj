(ns skyscraper.test-utils
  (:require
    [clojure.java.jdbc :as jdbc]
    [hiccup.page :refer [html5]]
    [ring.adapter.jetty :refer [run-jetty]]
    [ring.util.response :as response]))

;; ring

(def port 64738) ;; let's hope it's not used

(defn make-seed
  ([processor] (make-seed processor "/"))
  ([processor initial-url] [{:processor processor
                             :url (str "http://localhost:" port initial-url)}]))

(def srv (atom nil))

(defn start-server [handler]
  (reset! srv (run-jetty handler {:port port, :join? false})))

(defmacro with-server [handler & body]
  `(let [server# (run-jetty ~handler {:port port, :join? false})]
     (try
       ~@body
       (finally
         (.stop server#)))))

(defmacro resp-page [& body]
  `(response/response (html5 ~@body)))

;; sqlite

(defn temporary-sqlite-db-file []
  (java.io.File/createTempFile "test" ".sqlite"))

(defn sqlite-file->uri [file]
  (str "jdbc:sqlite:" file))

(defmacro with-temporary-sqlite-db [conn & body]
  `(let [db-file# (temporary-sqlite-db-file)
         db-uri# (sqlite-file->uri db-file#)]
     (try
       (jdbc/with-db-connection [~conn db-uri#]
         ~@body)
       (finally
         (.delete db-file#)))))

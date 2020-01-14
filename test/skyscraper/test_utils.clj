(ns skyscraper.test-utils
  (:require
    [clojure.java.jdbc :as jdbc]))

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

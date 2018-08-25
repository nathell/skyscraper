(ns skyscraper.sqlite
  (:require [clojure.core.strint :refer [<<]]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as string]
            [taoensso.timbre :refer [debugf]]))

(defn db-column-name [col]
  (string/replace (name col) "-" "_"))

(defn db-row [columns context]
  (into {}
        (map (fn [[k v]] [(db-column-name k) v]))
        (select-keys context columns)))

(defn create-table [db name columns]
  (jdbc/execute!
   db
   (jdbc/create-table-ddl
    name
    (into [[:id :integer "primary key"]
           [:parent :integer]]
          (for [col columns :when (not= col :parent)]
            [(db-column-name col) :text])))))

(def rowid (keyword "last_insert_rowid()"))

(defn query [db name id ctxs]
  (let [id-part (string/join ", " (map db-column-name id))
        values-1 (str "(" (string/join ", " (repeat (count id) "?")) ")")
        values (string/join ", " (repeat (count ctxs) values-1))
        query (<< "select * from ~(db-column-name name) where (~{id-part}) in (values~{values})")
        params (mapcat (apply juxt id) ctxs)]
    (jdbc/query db (into [query] params))))

(defn all-nils? [id context]
  (every? nil? (map context id)))

(defn insert-all! [db name id columns ctxs]
  (let [name (db-column-name name)
        ctxs (if id
               (remove (partial all-nils? id) ctxs)
               ctxs)
        existing (when id (try (query db name id ctxs)
                               (catch org.sqlite.SQLiteException e nil)))
        existing-ids (when id (set (map #(select-keys % id) existing)))
        new-contexts (remove #(contains? existing-ids (select-keys % id)) ctxs)
        to-insert (map (partial db-row columns) new-contexts)
        mutex (Object.)
        try-inserting (fn []
                        (let [ids (map rowid (locking mutex (jdbc/insert-multi! db name to-insert)))]
                          (mapv #(assoc %1 :parent %2) new-contexts ids)))]
    (debugf "[sqlite] Got %s, existing %s, inserting %s" (count ctxs) (count existing-ids) (count to-insert))
    (try
      (try-inserting)
      (catch org.sqlite.SQLiteException e
        (if (re-find #"no such table" (.getMessage e))
          (do
            (create-table db name columns)
            (try-inserting))
          (throw e))))))

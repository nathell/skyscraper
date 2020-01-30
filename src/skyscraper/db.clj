(ns skyscraper.db
  (:require
    [clojure.core.async :as async]
    [clojure.core.strint :refer [<<]]
    [clojure.java.jdbc :as jdbc]
    [clojure.set :as set]
    [clojure.string :as string]
    [skyscraper.context :as context]
    [skyscraper.data :refer [separate]]
    [taoensso.timbre :refer [debugf warnf]]))

(defn- keyword->db-name
  "Converts a keyword (naming a DB table or column) to a string
  suitable for use in SQL queries."
  [k]
  (string/replace (name k) "-" "_"))

(defn- db-name->keyword
  "The inverse of keyword->db-name."
  [str]
  (keyword (string/replace str "_" "-")))

(defn- create-index-ddl
  "Returns SQL to create an index on a given table and columns. Note that
  Skyscraper only creates one index per table, so it's sufficient to just
  name it after the table."
  [table-name key-column-names]
  (let [index-name (str "idx_" table-name)]
    (str "CREATE UNIQUE INDEX " index-name " ON " table-name " (" (string/join ", " key-column-names) ")")))

(defn- create-table
  "Creates a table in db containing the given column-names. If key-column-names
  is non-empty, also creates a unique index on those columns."
  [db table-name column-names key-column-names]
  (jdbc/execute! db
                 (jdbc/create-table-ddl
                  table-name
                  (into [["id" :integer "primary key"]
                         ["parent" :integer]]
                        (for [col column-names :when (not= col "parent")]
                          [col :text]))))
  (when (seq key-column-names)
    (jdbc/execute! db
                   (create-index-ddl table-name key-column-names))))

(defn- query-context-ids
  "Selects the rows corresponding to the upserted contexts, to retrieve
  their database-assigned IDs."
  [db table-name key-columns key-column-names ctxs]
  (let [key-part (string/join ", " key-column-names)
        values-1 (str "(" (string/join ", " (repeat (count key-column-names) "?")) ")")
        values (string/join ", " (repeat (count ctxs) values-1))
        null-clause (string/join " or " (map #(str % " is null") key-column-names)) ;; XXX: this might return too broad a result set
        query (<< "select * from ~{table-name} where (~{key-part}) in (values~{values}) or ~{null-clause}") ;; XXX: only select id + key columns, not *
        params (mapcat (apply juxt key-columns) ctxs)]
    (jdbc/query db (into [query] params)
                {:identifiers db-name->keyword})))

(defn- upsert-multi-row-sql
  "Returns SQL for upsert-multi!"
  [table-name column-names key-column-names values]
  (let [nc (count column-names)
        vcs (map count values)
        non-key-column-names (vec (set/difference (set column-names) (set key-column-names)))
        comma-join (partial string/join ", ")
        qmarks (repeat (first vcs) "?")]
    (if (not (and (or (zero? nc) (= nc (first vcs))) (apply = vcs)))
      (throw (IllegalArgumentException. "insert! called with inconsistent number of columns / values"))
      (into [(str (<< "INSERT INTO ~{table-name} (~(comma-join column-names)) VALUES (~(comma-join qmarks))")
                  (when (seq key-column-names)
                    (let [set-clause (string/join ", " (map #(str % " = excluded." %) non-key-column-names))
                          do-clause (if (empty? non-key-column-names)
                                      "NOTHING"
                                      (str "UPDATE SET " set-clause))]
                      (<< " ON CONFLICT (~(comma-join key-column-names)) DO ~{do-clause}"))))]
            values))))

(defn- upsert-multi!
  "Like clojure.java.jdbc/insert-multi!, but updates the existing rows
  where key-column-names match supplied ones. Requires rows to be a
  sequence of vectors. Not wrapped in a transaction.
  Equivalent to insert-multi! if key-column-names is empty.
  Note: This is currently implemented as an INSERT ... ON CONFLICT DO
  UPDATE, which requires a DBMS able to support this syntax (SQLite
  3.24+ or PostgreSQL 9.5+)."
  [db table-name column-names key-column-names rows]
  (jdbc/db-do-prepared db false
                       (upsert-multi-row-sql table-name column-names key-column-names rows)
                       {:multi? true}))

(defn- upsert-multi-ensure-table!
  "Tries an upsert-multi!, and if it fails due to a missing table,
  creates it and tries again."
  [db table-name column-names key-column-names rows]
  (try
    (upsert-multi! db table-name column-names key-column-names rows)
    (catch org.sqlite.SQLiteException e
      (if (re-find #"no such table" (.getMessage e))
        (do
          (create-table db table-name column-names key-column-names)
          (upsert-multi! db table-name column-names key-column-names rows))
        (throw e)))))

(defn- ensure-types-single
  "Returns context, emitting warnings if the fields named by columns
  don't exist or are not of expected type (int for :parent, nilable string
  otherwise)."
  [columns context]
  (doseq [[k v] context
          :when (contains? columns k)
          :let [check (if (= k :parent) int? #(or (nil? %) (string? %)))]
          :when (not (check v))]
    (warnf "Wrong type for key %s, value %s" k v))
  (doseq [column columns
          :when (and (not= column :parent)
                     (not (contains? context column)))]
    (warnf "Context contains no value for key %s: %s" column (context/describe context)))
  (merge (zipmap columns (repeat nil))
         context))

(defn- ensure-types
  "Ensures types of all contexts as per ensure-types-single."
  [columns ctxs]
  (mapv (partial ensure-types-single columns) ctxs))

(defn- extract-ids
  "Given a sequence of ctxs that are assumed to exist in the given db table,
  queries the DB for them and assocs each one's id as :parent."
  ;; Remember that this runs after the processor's :process-fn, so
  ;; calling it :parent ensures that the child processors will encounter
  ;; this in the expected place.
  [db table-name key-columns key-column-names ctxs]
  (let [inserted-rows (query-context-ids db table-name key-columns key-column-names ctxs)
        inserted-row-ids (into {}
                               (map (fn [r] [(select-keys r key-columns) (:id r)]))
                               inserted-rows)]
    (map (fn [ctx]
           (assoc ctx :parent (get inserted-row-ids (select-keys ctx key-columns))))
         ctxs)))

(defn- extract-ids-from-last-rowid
  "Given a sequence of ctxs that have just been successfully inserted,
  assocs each one's id in the DB as :parent based on last_insert_rowid()
  (SQLite-specific)."
  [db ctxs]
  (let [rowid (-> (jdbc/query db "select last_insert_rowid() rowid") first :rowid)]
    (map #(assoc %1 :parent %2) ctxs (range (inc (- rowid (count ctxs))) (inc rowid)))))

(defn upsert-contexts
  "Inserts new contexts into a given db table, returning them augmented
  with the `:parent` fields corresponding to the DB-generated primary
  keys.  If `key-columns` (a vector of column names) is provided,
  does an upsert rather than an insert, checking for conflicts on
  those columns and updating db accordingly."
  [db table key-columns columns ctxs]
  (debugf "Upserting %s rows" (count ctxs))
  (when (seq ctxs)
    (let [ctxs (ensure-types (set columns) ctxs)
          table-name (keyword->db-name table)
          column-names (mapv keyword->db-name columns)
          key-column-names (mapv keyword->db-name key-columns)
          rows (map (apply juxt columns) ctxs)]
      (upsert-multi-ensure-table! db table-name column-names key-column-names rows)
      (if (seq key-column-names)
        (extract-ids db table-name key-columns key-column-names ctxs)
        (extract-ids-from-last-rowid db ctxs)))))

(defn maybe-store-in-db
  "Wraps upsert-context, skipping contexts that contain ::skip."
  [db {:keys [name ::columns ::key-columns] :as processor} contexts]
  (if (and db columns)
    (let [columns (distinct (conj columns :parent))
          [skipped inserted] (separate ::skip contexts)
          new-items (upsert-contexts db name key-columns columns inserted)]
      (into (vec skipped) new-items))
    contexts))

(defn enhancer
  "An enhancer that upserts supplied batches of contexts into
  the database."
  [{:keys [db]} {:keys [enhancer-input-chan enhancer-output-chan]}]
  (jdbc/with-db-transaction [db db]
    (loop []
      (when-let [item (async/<!! enhancer-input-chan)]
        (let [new-items (:skyscraper.core/new-items item)
              updated (maybe-store-in-db db (:skyscraper.core/current-processor item) new-items)]
          (async/>!! enhancer-output-chan (assoc item :skyscraper.core/new-items updated)))
        (recur)))))

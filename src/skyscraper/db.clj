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

(defn db-name [col]
  (string/replace (name col) "-" "_"))

(defn normalize-keys [m]
  (into {}
        (map (fn [[k v]] [(keyword (string/replace (name k) "_" "-")) v]))
        m))

(defn db-row [columns context]
  (into {}
        (map (fn [[k v]] [(db-name k) v]))
        (select-keys context columns)))

(defn create-index-ddl [table-name key-column-names]
  (let [index-name (str "idx_" table-name)]
    (str "CREATE UNIQUE INDEX " index-name " ON " table-name " (" (string/join ", " key-column-names) ")")))

(defn create-table [db table-name column-names key-column-names]
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

(defn query [db table-name id ctxs]
  (let [id-part (string/join ", " (map db-name id))
        values-1 (str "(" (string/join ", " (repeat (count id) "?")) ")")
        values (string/join ", " (repeat (count ctxs) values-1))
        query (<< "select * from ~{table-name} where (~{id-part}) in (values~{values})")
        params (mapcat (apply juxt id) ctxs)]
    (map normalize-keys
         (jdbc/query db (into [query] params)))))

(defn- upsert-multi-row-sql
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
                    (let [set-clause (string/join ", " (map #(str % " = excluded." %) non-key-column-names))]
                      (<< " ON CONFLICT (~(comma-join key-column-names)) DO UPDATE SET ~{set-clause}"))))]
            values))))

(defn upsert-multi! [db table-name column-names key-column-names rows]
  (jdbc/db-do-prepared db false
                       (upsert-multi-row-sql table-name column-names key-column-names rows)
                       {:multi? true}))

(defn upsert-multi-ensure-table! [db table-name column-names key-column-names rows]
  (try
    (upsert-multi! db table-name column-names key-column-names rows)
    (catch org.sqlite.SQLiteException e
      (if (re-find #"no such table" (.getMessage e))
        (do
          (create-table db table-name column-names key-column-names)
          (upsert-multi! db table-name column-names key-column-names rows))
        (throw e)))))

(defn all-nils? [id context]
  (every? nil? (map context id)))

(defn ensure-types-single [columns context]
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

(defn ensure-types [columns ctxs]
  (mapv (partial ensure-types-single columns) ctxs))

(defn separate-by
  "Returns [matching non-matching], where matching is a vector of
  (f x) for x in coll, and non-matching is a vector of x's such that
  (f x) returns nil."
  [f coll]
  (reduce (fn [[matching non-matching] x]
            (if-let [res (f x)]
              [(conj matching res) non-matching]
              [matching (conj non-matching x)]))
          [[] []]
          coll))

(defn extract-ids [db table-name key-columns ctxs]
  (let [inserted-rows (query db table-name key-columns ctxs)
        inserted-row-ids (into {}
                               (map (fn [r] [(select-keys r key-columns) (:id r)]))
                               inserted-rows)]
    (map (fn [ctx]
           (assoc ctx :parent (get inserted-row-ids (select-keys ctx key-columns))))
         ctxs)))

(defn extract-ids-from-last-rowid [db ctxs]
  (let [rowid (-> (jdbc/query db "select last_insert_rowid() rowid") first :rowid)]
    (map #(assoc %1 :parent %2) ctxs (range (inc (- rowid (count ctxs))) (inc rowid)))))

(defn insert-all!
  "Inserts new contexts into a given db table, returning them augmented
  with the `:id` fields corresponding to the DB-generated primary
  keys.  If `key-columns` (a vector of column names) is provided,
  checks for the presence of matching contexts in DB and only inserts
  those that were not present."
  [db table key-columns columns ctxs]
  (debugf "Upserting %s rows" (count ctxs))
  (let [ctxs (ensure-types (set columns) ctxs)
        table-name (db-name table)
        column-names (mapv db-name columns)
        key-column-names (mapv db-name key-columns)
        rows (map (apply juxt columns) ctxs)]
    (upsert-multi-ensure-table! db table-name column-names key-column-names rows)
    (if (seq key-column-names)
      (extract-ids db table-name key-columns ctxs)
      (extract-ids-from-last-rowid db ctxs))))

(defn maybe-store-in-db [db {:keys [name db-columns id] :as q} contexts]
  (if (and db db-columns)
    (let [db-columns (distinct (conj db-columns :parent))
          [skipped inserted] (separate :skyscraper.core/db-skip contexts)
          new-items (insert-all! db name id db-columns inserted)]
      (into (vec skipped) new-items))
    contexts))

(defn enhancer [{:keys [db]} {:keys [enhancer-input-chan enhancer-output-chan]}]
  (jdbc/with-db-transaction [db db]
    (loop []
      (when-let [item (async/<!! enhancer-input-chan)]
        (let [new-items (:skyscraper.core/new-items item)
              updated (maybe-store-in-db db (:skyscraper.core/current-processor item) new-items)]
          (async/>!! enhancer-output-chan (assoc item :skyscraper.core/new-items updated)))
        (recur)))))

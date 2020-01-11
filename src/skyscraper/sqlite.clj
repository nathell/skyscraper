(ns skyscraper.sqlite
  (:require
    [clojure.core.async :as async]
    [clojure.core.strint :refer [<<]]
    [clojure.java.jdbc :as jdbc]
    [clojure.string :as string]
    [skyscraper.context :as context]
    [skyscraper.data :refer [separate]]
    [taoensso.timbre :refer [debugf warnf]]))

(defn db-column-name [col]
  (string/replace (name col) "-" "_"))

(defn normalize-keys [m]
  (into {}
        (map (fn [[k v]] [(keyword (string/replace (name k) "_" "-")) v]))
        m))

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

(defn ensure-types-single [columns context]
  (doseq [[k v] context
          :when (contains? columns k)
          :let [check (if (= k :parent) int? #(or (nil? %) (string? %)))]
          :when (not (check v))]
    (warnf "[sqlite] Wrong type for key %s, value %s" k v))
  (doseq [column columns
          :when (and (not= column :parent)
                     (not (contains? context column)))]
    (warnf "[sqlite] Context contains no value for key %s: %s" column (context/describe context)))
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

(defn insert-all! [db name id columns ctxs]
  (let [ctxs (ensure-types (set columns) ctxs)
        name (db-column-name name)
        existing (when id (try (map normalize-keys (query db name id ctxs))
                               (catch org.sqlite.SQLiteException e nil)))
        existing-ids (into {} (map (fn [r] [(select-keys r id) (:id r)])) existing)
        [contexts-to-preserve new-contexts] (separate-by (fn [ctx]
                                                           (let [existing-id (existing-ids (select-keys ctx id))]
                                                             (cond
                                                               existing-id (assoc ctx :parent existing-id)
                                                               :else nil)))
                                                         ctxs)
        to-insert (map (partial db-row columns) new-contexts)
        try-inserting (fn []
                        (let [ids (map rowid (locking db (jdbc/insert-multi! db name to-insert)))]
                          (mapv #(assoc %1 :parent %2) new-contexts ids)))]
    (debugf "[sqlite] Got %s, existing %s, inserting %s" (count ctxs) (count existing-ids) (count to-insert))
    (let [inserted (try
                     (try-inserting)
                     (catch org.sqlite.SQLiteException e
                       (if (re-find #"no such table" (.getMessage e))
                         (do
                           (create-table db name columns)
                           (try-inserting))
                         (throw e))))]
      (debugf "[sqlite] Returning %s" (count (into contexts-to-preserve inserted)))
      (into contexts-to-preserve inserted))))

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

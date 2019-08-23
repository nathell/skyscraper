(ns skyscraper
  (:require
    [clj-http.client :as http]
    [clj-http.conn-mgr :as http-conn]
    [clj-http.core :as http-core]
    [clojure.set :refer [intersection]]
    [clojure.string :as string]
    [net.cgrand.enlive-html :as enlive]
    [reaver]
    [skyscraper.cache :as cache]
    [skyscraper.sqlite :as sqlite]
    [skyscraper.traverse :as traverse]
    [taoensso.timbre :refer [debugf infof warnf errorf]])
  (:import [java.net URL]))

;;; Directories

(def output-dir
  "All Skyscraper output, either temporary or final, goes under here."
  (str (System/getProperty "user.home") "/skyscraper-data/"))

(def html-cache-dir
  "Local copies of downloaded HTML files go here."
  (str output-dir "cache/html/"))

(def processed-cache-dir
  "Cache storing the interim results of processing HTML files."
  (str output-dir "cache/processed/"))

;;; Micro-templating framework

(defn format-template
  "Fills in a template string with moving parts from m. template should be
   a string containing 'variable names' starting with colons; these names
   are extracted, converted to keywords and looked up in m, which should be
   a map (or a function taking keywords and returning strings).

   Example:
   (format-template \":group/:user/index\" {:user \"joe\", :group \"admins\"})
   ;=> \"admins/joe/index\" "
  [template m]
  (let [re #":[a-z-]+"
        keys (map #(keyword (subs % 1)) (re-seq re template))
        fmt (string/replace template re "%s")]
    (apply format fmt (map m keys))))

(defn cache-key [{:keys [cache-template cache-key-fn]} context]
  (let [cache-key-fn (or cache-key-fn
                         (when cache-template
                           (partial format-template cache-template)))]
    (when cache-key-fn
      (cache-key-fn context))))

;;; Cache

(defn sanitize-cache
  "Converts a cache argument to the processor to a CacheBackend if it
   isn't one already."
  [value cache-dir]
  (cond
   (= value true) (cache/fs cache-dir)
   (not value) (cache/null)
   :otherwise value))

;;; Defining processors

(defonce processors (atom {}))

(defn defprocessor [name & {:keys [process-fn], :as args}]
  (swap! processors assoc name (merge {:name name} args)))

(defn ensure-seq [x]
  (if (map? x) [x] (doall x)))

(defn run-processor
  ([processor-name document] (run-processor processor-name document {}))
  ([processor-name document context]
   (let [processor (@processors processor-name)]
     (ensure-seq ((:process-fn processor) document context)))))

(defn dissoc-internal [ctx]
  (let [removed-keys #{:method :processor :desc :form-params ::new-items}]
    (into {}
          (remove (fn [[k _]] (or (contains? removed-keys k))))
          ctx)))

(defn allows?
  "True if all keys in m1 that are also in m2 have equal values in both maps."
  [m1 m2]
  (let [ks (intersection (set (keys m1)) (set (keys m2)))]
    (if (seq ks)
      (let [f (apply juxt ks)]
        (= (f m1) (f m2)))
      true)))

(defn filter-contexts
  [data params]
  (if-let [only (:only params)]
    (let [filter-fn (if (fn? only)
                      only
                      (fn [x] (some #(allows? % x) (ensure-seq only))))]
      (filter filter-fn data))
    data))

(defn merge-urls
  "Fills the missing parts of new-url (which can be either absolute,
   root-relative, or relative) with corresponding parts from url
   (an absolute URL) to produce a new absolute URL."
  [url new-url]
  (if (string/starts-with? new-url "?")
    (str (string/replace url #"\?.*" "") new-url)
    (str (URL. (URL. url) new-url))))

(defn merge-contexts [old new]
  (let [preserve (dissoc-internal old)
        new-url (if-let [u (:url new)]
                  (merge-urls (:url old) u))
        new (if new-url
              (assoc new :url new-url)
              new)]
    (merge preserve new)))

(defn describe [ctx]
  (:url ctx))

(defn string-resource
  "Returns an Enlive resource for a HTML snippet passed as a string."
  [s]
  (enlive/html-resource (java.io.StringReader. s)))

;;; Scraping

(defn extract-namespaced-keys
  [ns m]
  (into {}
        (comp (filter #(= (namespace (key %)) ns))
              (map (fn [[k v]] [(keyword (name k)) v])))
        m))

(defn make-pipeline [options]
  `[init-handler
    check-cache-handler
    download-handler
    store-cache-handler
    process-handler
    split-handler])

(defn advance-pipeline [pipeline context]
  (let [next-stage (or (::next-stage context)
                       (->> pipeline
                            (drop-while #(not= % (::stage context)))
                            second)
                       (when (:processor context)
                         (first pipeline)))]
    (if next-stage
      (-> context
          (dissoc ::next-stage)
          (assoc ::stage next-stage
                 ::traverse/handler (if (= next-stage `download-handler)
                                      `download-handler
                                      `sync-handler)
                 ::traverse/call-protocol (if (= next-stage `download-handler)
                                            :callback
                                            :sync)))
      (dissoc context ::stage ::next-stage ::traverse/handler ::traverse/call-protocol ::response ::cache-key))))

(defn init-handler [context options]
  (let [{:keys [cache-template cache-key-fn]} (merge options (@processors (:processor context)))
        cache-key-fn (or cache-key-fn
                         (when cache-template
                           #(format-template cache-template %)))]
    [(assoc context
            ::current-processor (@processors (:processor context))
            ::cache-key (cache-key-fn context))]))

(defn check-cache-handler [context options]
  (if-let [key (::cache-key context)]
    (if-let [item (cache/load-string (:html-cache options) key)]
      [(assoc context
              ::response {:body item}
              ::next-stage `process-handler)]
      [context])
    [context]))

(defn download-handler [context {:keys [pipeline connection-manager download-semaphore retries] :as options} callback]
  (let [req (merge {:method :get, :url (:url context)}
                   (extract-namespaced-keys "http" context))
        success-fn (fn [resp]
                     (debugf "[download] Downloaded %s" (describe context))
                     (.release download-semaphore)
                     (callback
                      [(cond-> (advance-pipeline pipeline context)
                         true (assoc ::response resp)
                         (:cookies resp) (update :http/cookies merge (:cookies resp)))]))
        error-fn (fn [error]
                   (.release download-semaphore)
                   (let [retry (inc (or (::retry context) 0))]
                     (callback
                      [(if (< retry (:retries options))
                          (do
                            (warnf "[download] Unexpected error %s, retry %s, context %s" error retry context)
                            (assoc context ::retry retry))
                          (do
                            (warnf "[download] Unexpected error %s, giving up, context %s" error context)
                            {::error error, ::context context}))])))]
    (debugf "[download] Waiting")
    (.acquire download-semaphore)
    (infof "[download] Downloading %s" (describe context))
    (let [req (merge {:async? true,
                      :connection-manager connection-manager}
                     req (:http-options options))]
      (http/request req
       success-fn
       error-fn))))

(defn store-cache-handler [context options]
  (cache/save-string (:html-cache options) (::cache-key context) (get-in context [::response :body]))
  [context])

(defn process-handler [context options]
  (if-let [cached-result (cache/load (:processed-cache options) (::cache-key context))]
    [(assoc context ::new-items (map (partial merge-contexts context) cached-result))]
    (let [parse (:parse-fn options)
          document (-> context ::response :body parse)
          processor-name (:processor context)
          result (ensure-seq (run-processor processor-name document context))]
      (cache/save (:processed-cache options) (::cache-key context) result)
      [(assoc context ::new-items (map (partial merge-contexts context) result))])))

(defn split-handler [context options]
  (map #(assoc % ::stage `split-handler)
       (::new-items context)))

(defn sync-handler [context options]
  (let [f (ns-resolve *ns* (::stage context))
        results (f context options)]
    (map (partial advance-pipeline (:pipeline options)) results)))

(defn initialize-seed [options seed]
  (map #(advance-pipeline (:pipeline options) %)
       (ensure-seq seed)))

(def default-options
  {:max-connections 10,
   :timeout 60000,
   :retries 5,
   :conn-mgr-options {},
   :parse-fn string-resource,
   :http-options {:redirect-strategy :lax, :as :auto}})

(defn initialize-options
  [options]
  (let [options (merge default-options options)
        db (or (:db options)
               (when-let [file (:db-file options)]
                 {:classname "org.sqlite.JDBC",
                  :subprotocol "sqlite",
                  :subname file}))]
    (assoc options
           :pipeline (make-pipeline options)
           :db db
           :enhancer (when db sqlite/enhancer)
           :enhance? ::new-items
           :html-cache (sanitize-cache (:html-cache options) html-cache-dir)
           :processed-cache (sanitize-cache (:processed-cache options) processed-cache-dir)
           :connection-manager (http-conn/make-reuseable-async-conn-manager (:conn-mgr-options options))
           :download-semaphore (java.util.concurrent.Semaphore. (:max-connections options)))))

(defn scrape [seed & {:as options}]
  (let [options (initialize-options options)
        seed (initialize-seed options seed)]
    (traverse/leaf-seq seed options)))

(defn scrape! [seed & {:as options}]
  (let [options (initialize-options options)
        seed (initialize-seed options seed)]
    (if (:db options)
      (jdbc/with-db-transaction [db (:db options)]
        (traverse/traverse! seed (assoc options :db db)))
      (traverse/traverse! seed options))))

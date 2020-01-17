(ns skyscraper.core
  (:require
    [clj-http.client :as http]
    [clj-http.conn-mgr :as http-conn]
    [clj-http.core :as http-core]
    [clojure.edn :as edn]
    [clojure.set :refer [intersection]]
    [clojure.string :as string]
    [net.cgrand.enlive-html :as enlive]
    [reaver]
    [skyscraper.cache :as cache]
    [skyscraper.context :as context]
    [skyscraper.db :as sqlite]
    [skyscraper.traverse :as traverse]
    [taoensso.timbre :refer [debugf infof warnf errorf]])
  (:import [java.net URL]
           [java.nio.charset Charset]))

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

(defn default-process-fn
  [resource context]
  [{::unimplemented true, ::resource resource, ::context context}])

(defn defprocessor
  "Registers a processor named `name` with arguments `args`.

  `name` should be a keyword. `args`, optional keys and values, may include:

  - `:process-fn` – a function that takes a resource and a parent context, and returns a
    sequence of child contexts (corresponding to the scraped resource). Alternatively,
    it can return one context only, in which case it will be wrapped in a sequence.
  - `:cache-template` – a string specifying the template for cache keys. Ignored when
    `:cache-key-fn` is specified.
  - `:cache-key-fn` – a function taking the context and returning the cache key. Overrides
    `:cache-template`. Useful when mere templating does not suffice.
  - `:url-fn` – a one-argument function taking the context and returning the URL to visit.
    By default, Skyscraper just extracts the value under the `:url` key from the context.
  - `:error-handler` – see [error-handling.md].
  - `:updatable` – a boolean (false by default). When true, the pages accessed by this
    processor are considered to change often. When Skyscraper is run in update mode (see
    below), these pages will be re-downloaded and re-processed even if they had been present
    in the HTML or processed caches, respectively.
  - `:parse-fn` – a custom function that will be used to produce Enlive resources from
    downloaded documents. This can be useful, for instance, if you want to use reaver rather
    than Enlive; if you are scraping something other than HTMLs (e.g., PDFs via a custom
    parser); or when you’re scraping malformed HTML and need an interim fixup steps before
    parsing.
  - `:skyscraper.db/columns` – a vector of keys that are supposed to exist."
  [name & {:as args}]
  (swap! processors assoc name (merge {:name name, :process-fn default-process-fn} args)))

(defn ensure-distinct-seq [x]
  (if (map? x) [x] (doall (distinct x))))

(defn run-processor
  ([processor-name document] (run-processor processor-name document {}))
  ([processor-name document context]
   (let [processor (or (@processors processor-name)
                       {:name processor-name, :process-fn default-process-fn})]
     (ensure-distinct-seq ((:process-fn processor) document context)))))

(defn allows?
  "True if all keys in m1 that are also in m2 have equal values in both maps."
  [m1 m2]
  (let [ks (intersection (set (keys m1)) (set (keys m2)))]
    (if (seq ks)
      (let [f (apply juxt ks)]
        (= (f m1) (f m2)))
      true)))

(defn filter-contexts
  [options contexts]
  (if-let [only (:only options)]
    (let [filter-fn (if (fn? only)
                      only
                      (fn [x] (some #(allows? % x) (ensure-distinct-seq only))))]
      (filter filter-fn contexts))
    contexts))

(defn merge-urls
  "Fills the missing parts of new-url (which can be either absolute,
   root-relative, or relative) with corresponding parts from url
   (an absolute URL) to produce a new absolute URL."
  [url new-url]
  (if (string/starts-with? new-url "?")
    (str (string/replace url #"\?.*" "") new-url)
    (str (URL. (URL. url) new-url))))

(defn merge-contexts [old new]
  (let [preserve (context/dissoc-internal old)
        new-url (if-let [u (:url new)]
                  (merge-urls (:url old) u))
        new (if new-url
              (assoc new :url new-url)
              new)]
    (merge preserve new)))

(defn string-resource
  "Returns an Enlive resource for a HTML snippet passed as a string."
  [s]
  (enlive/html-resource (java.io.StringReader. s)))

(defn interpret-body
  [headers ^bytes body]
  (let [stream1 (java.io.ByteArrayInputStream. body)
        body-map (http/parse-html stream1)
        additional-headers (http/get-headers-from-body body-map)
        all-headers (merge headers additional-headers)
        content-type (get all-headers "content-type")]
    (String. body (Charset/forName (http/detect-charset content-type)))))

(defn enlive-parse
  [headers body]
  (string-resource (interpret-body headers body)))

(defn reaver-parse
  [headers body]
  (reaver/parse (interpret-body headers body)))

;;; Scraping

(defn extract-namespaced-keys
  [ns m]
  (into {}
        (comp (filter #(= (namespace (key %)) ns))
              (map (fn [[k v]] [(keyword (name k)) v])))
        m))

(defn make-pipeline [{:keys [download-mode] :as options}]
  (case download-mode
    :async `[init-handler check-cache-handler download-handler store-cache-handler process-handler split-handler]
    :sync  `[init-handler sync-download-handler store-cache-handler process-handler sync-split-handler]))

(defn compose-sync-handlers
  ([h] h)
  ([h1 h2]
   (fn [context options]
     (h1 (first (h2 context options)) options)))
  ([h1 h2 & hs]
   (compose-sync-handlers h1 (apply compose-sync-handlers h2 hs))))

(defn make-squashed-handler
  [pipeline]
  (apply compose-sync-handlers
         (map (partial ns-resolve *ns*) (reverse pipeline))))

(defn advance-pipeline [pipeline context]
  (let [next-stage (or (::next-stage context)
                       (->> pipeline
                            (drop-while #(not= % (::stage context)))
                            second)
                       (when (and (:processor context) (:url context))
                         (first pipeline)))]
    (when (and (:processor context) (not (:url context)))
      (warnf "Encountered context with processor but no URL: %s" (context/describe context)))
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
      (context/dissoc-leaf-keys context))))

(defn init-handler [context options]
  (let [{:keys [cache-template cache-key-fn]} (merge options (@processors (:processor context)))
        cache-key-fn (or cache-key-fn
                         #(when cache-template
                           (format-template cache-template %)))]
    [(assoc context
            ::current-processor (@processors (:processor context))
            ::cache-key (cache-key-fn context))]))

(defn maybe-retrieve-from-http-cache [context options]
  (if-let [key (::cache-key context)]
    (if-let [item (cache/load-blob (:html-cache options) key)]
      {:body (:blob item), :headers (:meta item)})))

(defn check-cache-handler [context options]
  (if-let [cached-response (maybe-retrieve-from-http-cache context options)]
    [(assoc context
            ::response cached-response
            ::next-stage `process-handler)]
    [context]))

(defn download-handler [context {:keys [pipeline connection-manager download-semaphore retries] :as options} callback]
  (debugf "Running download-handler: %s" (:processor context))
  (let [req (merge {:method :get, :url (:url context)}
                   (extract-namespaced-keys "http" context))
        success-fn (fn [resp]
                     (debugf "[download] Downloaded %s" (:url context))
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
                            (warnf "[download] Unexpected error %s, retry %s, context %s" error retry (context/describe context))
                            (assoc context ::retry retry))
                          (do
                            (warnf "[download] Unexpected error %s, giving up, context %s" error (context/describe context))
                            {::error error, ::context context}))])))]
    (debugf "[download] Waiting")
    (.acquire download-semaphore)
    (infof "[download] Downloading %s" (:url context))
    (let [req (merge {:async? true,
                      :connection-manager connection-manager}
                     req (:http-options options))
          request-fn (or (:request-fn options)
                         http/request)]
      (http/with-additional-middleware [http/wrap-lower-case-headers]
        (request-fn req
                    success-fn
                    error-fn)))))

(defn sync-download-handler [context {:keys [pipeline connection-manager] :as options}]
  (let [req (merge {:method :get, :url (:url context), :connection-manager connection-manager}
                   (extract-namespaced-keys "http" context)
                   (:http-options options))
        request-fn (or (:request-fn options)
                       http/request)]
    (try
      (infof "[download] Downloading %s" (:url context))
      (let [cached (maybe-retrieve-from-http-cache context options)
            resp (or cached
                     (http/with-additional-middleware [http/wrap-lower-case-headers]
                       (request-fn req)))]
        (debugf "[download] %s %s" (if cached "Retrieved from cache:" "Downloaded:") (:url context))
        [(cond-> context
           true (assoc ::response resp)
           (:cookies resp) (update :http/cookies merge (:cookies resp)))])
      (catch Exception error
        (let [retry (inc (or (::retry context) 0))]
          [(if (< retry (:retries options))
             (do
               (warnf "[download] Unexpected error %s, retry %s, context %s" error retry (context/describe context))
               (assoc context ::retry retry))
             (do
               (warnf "[download] Unexpected error %s, giving up, context %s" error (context/describe context))
               {::error error, ::context context}))])))))

(defn store-cache-handler [context options]
  (cache/save-blob (:html-cache options) (::cache-key context) (get-in context [::response :body]) (get-in context [::response :headers]))
  [context])

(defn process-handler [context options]
  (if-let [cached-result (cache/load-blob (:processed-cache options) (::cache-key context))]
    [(assoc context ::new-items (map (partial merge-contexts context) (edn/read-string (String. (:blob cached-result)))))]
    (let [parse (:parse-fn options)
          {:keys [headers body]} (::response context)
          document (parse headers body)
          processor-name (:processor context)
          result (run-processor processor-name document context)]
      (cache/save-blob (:processed-cache options) (::cache-key context) (.getBytes (pr-str result)) nil)
      [(assoc context ::new-items (map (partial merge-contexts context) result))])))

(defn split-handler [context options]
  (->> (::new-items context)
       (map #(assoc % ::stage `split-handler))
       (filter-contexts options)))

(defn sync-split-handler [context options]
  (->> (::new-items context)
       (filter-contexts options)
       (map #(if (and (:processor %) (:url %))
               %
               (context/dissoc-leaf-keys %)))))

(defn sync-handler [context options]
  (debugf "Running sync-handler: %s %s" (::stage context) (:processor context))
  (let [f (ns-resolve *ns* (::stage context))
        results (f context options)]
    (map (partial advance-pipeline (:pipeline options)) results)))

(defn initialize-seed [{:keys [download-mode pipeline] :as options} seed]
  (let [seed (ensure-distinct-seq seed)]
    (case download-mode
      :async (mapv #(advance-pipeline pipeline %) seed)
      :sync (mapv #(assoc %
                          ::traverse/call-protocol :sync
                          ::traverse/handler (make-squashed-handler pipeline))
                  seed))))

(def default-options
  {:max-connections 10,
   :retries 5,
   :conn-mgr-options {},
   :parse-fn enlive-parse,
   :download-mode :async,
   :http-options {:redirect-strategy :lax,
                  :as :byte-array,
                  :socket-timeout 30000,
                  :connection-timeout 30000}})

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
           :connection-manager (case (:download-mode options)
                                 :sync (http-conn/make-reusable-conn-manager (:conn-mgr-options options))
                                 :async (http-conn/make-reuseable-async-conn-manager (:conn-mgr-options options)))
           :download-semaphore (java.util.concurrent.Semaphore. (:max-connections options)))))

(defn scrape [seed & {:as options}]
  (let [options (initialize-options options)
        seed (initialize-seed options seed)]
    (traverse/leaf-seq seed options)))

(defn scrape! [seed & {:as options}]
  (let [options (initialize-options options)
        seed (initialize-seed options seed)]
    (traverse/traverse! seed options)))

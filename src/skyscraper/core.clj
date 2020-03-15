(ns skyscraper.core
  (:require
    [clj-http.client :as http]
    [clj-http.conn-mgr :as http-conn]
    [clj-http.core :as http-core]
    [clj-http.headers :as http-headers]
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

(defn- format-template
  "Fills in a template string with moving parts from m. template should be
  a string containing 'variable names' starting with colons; these names
  are extracted, converted to keywords and looked up in m, which should be
  a map (or a function taking keywords and returning strings).

  Example:
  ```clojure
  (format-template \":group/:user/index\" {:user \"joe\", :group \"admins\"})
  ;=> \"admins/joe/index\"
  ```"
  [template m]
  (let [re #":[a-z-]+"
        keys (map #(keyword (subs % 1)) (re-seq re template))
        fmt (string/replace template re "%s")]
    (apply format fmt (map m keys))))

;;; Cache

(defn- sanitize-cache
  "Converts a cache argument to the processor to a CacheBackend if it
   isn't one already."
  [value cache-dir]
  (cond
    (string? value) (cache/fs value)
    (= value true) (cache/fs cache-dir)
    (not value) (cache/null)
    :otherwise value))

;;; Defining processors

(defonce
  ^{:doc "The global registry of processors: an atom containing a map from
          keywords naming processors to the processor definitions."}
  processors (atom {}))

(defmacro with-processor-definitions
  "Runs body with processors defined as defs, restoring previous definitions
  afterwards.
  Note: don't use this unless you're skyscraper.dev."
  [defs & body]
  `(let [previous# @processors]
     (try
       (reset! processors ~defs)
       ~@body
       (finally (reset! processors previous#)))))

(defn- default-process-fn
  "The default function that becomes a processor's :process-fn
  if you don't specify one."
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
  - `:updatable` – a boolean (false by default). When true, the pages accessed by this
    processor are considered to change often. When Skyscraper is run in update mode (see
    below), these pages will be re-downloaded and re-processed even if they had been present
    in the HTML or processed caches, respectively.
  - `:parse-fn` – a custom function that will be used to produce Enlive resources from
    downloaded documents. This can be useful, for instance, if you want to use reaver rather
    than Enlive; if you are scraping something other than HTMLs (e.g., PDFs via a custom
    parser); or when you’re scraping malformed HTML and need an interim fixup steps before
    parsing.
  - `:skyscraper.db/columns` – a vector of keys that are supposed to exist in the resulting
    contexts; the corresponding values will be emitted as a database row when `:db` or
    `:db-file` is supplied as a scrape argument.
  - `:skyscraper.db/key-columns` – a vector of keys that, when
    supplied, will be used to upsert records to database and treated as
    a unique key to match existing database records against."
  [name & {:as args}]
  (swap! processors assoc name (merge {:name name, :process-fn default-process-fn} args)))

(defn- get-option
  "Some options can be specified either in the processor definition or
  during scraping; in this case, the per-processor one takes precedence."
  ([context options k] (get-option context options k nil))
  ([context options k default]
   (or (get-in context [::current-processor k])
       (get options k)
       default)))

(defn- ensure-distinct-seq
  "If x is a sequence, removes duplicates from it, else returns a vector
  containing x only."
  [x]
  (if (map? x) [x] (doall (distinct x))))

(defn run-processor
  "Runs a processor named by processor-name on document."
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

(defn- filter-contexts
  "If `:only` was supplied in `options`, returns `contexts` filtered by it
  as specified in the docstring of `scrape`, else returns all contexts."
  [{:keys [only] :as options} contexts]
  (if only
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

(defn- merge-contexts
  "Given two contexts, `old` as passed to a processor as input, and
  `new` as returned by the processor, returns a merged context that
  will be fed to child processors."
  [old new]
  (let [preserve (context/dissoc-internal old)
        new-url (if-let [u (:url new)]
                  (merge-urls (:url old) u))
        new (if new-url
              (assoc new :url new-url)
              new)]
    (merge preserve new)))

(defn- string-resource
  "Returns an Enlive resource for a HTML snippet passed as a string."
  [s]
  (enlive/html-resource (java.io.StringReader. s)))

(defn parse-string
  "Parses `body`, a byte-array, as a string encoded with
  content-type provided in `headers`. If `try-html?` is true,
  tries to look for encoding in the <meta http-equiv> tag
  in `body`."
  ([headers ^bytes body _context] (parse-string headers body _context false))
  ([headers ^bytes body _context try-html?]
   (let [stream1 (java.io.ByteArrayInputStream. body)
         body-map (http/parse-html stream1)
         additional-headers (if try-html?
                              (http/get-headers-from-body body-map)
                              {})
         all-headers (merge headers additional-headers)
         content-type (get all-headers "content-type")]
     (String. body (Charset/forName (http/detect-charset content-type))))))

(defn parse-enlive
  "Parses a byte array as a Enlive resource."
  [headers body _context]
  (string-resource (parse-string headers body _context true)))

(defn parse-reaver
  "Parses a byte array as a JSoup/Reaver document."
  [headers body _context]
  (reaver/parse (parse-string headers body _context true)))

;;; Scraping

(defn- extract-namespaced-keys
  "Filters `m`, returning a map with only the keys whose namespace is `ns`."
  [ns m]
  (into {}
        (comp (filter #(= (namespace (key %)) ns))
              (map (fn [[k v]] [(keyword (name k)) v])))
        m))

;; The scraping engine is implemented on top of skyscraper.traverse,
;; but each step (download, parse, run processor, store in cache) is
;; decomposed into several stages collectively known as a "pipeline".
;; Steps in the pipeline normally run from left to right, mostly
;; sequentially (except for `download-handler` which is async), and
;; after the last step, we return to the first one. The current stage
;; is stored as `::stage` in the context. A handler can override
;; the next one by setting `::next-stage`.

(defn- make-pipeline
  "Returns a list of symbols naming functions that implement the pipeline steps."
  [{:keys [download-mode] :as options}]
  (case download-mode
    :async `[init-handler check-cache-handler download-handler store-cache-handler process-handler split-handler]
    :sync  `[init-handler sync-download-handler store-cache-handler process-handler sync-split-handler]))

(defn- compose-sync-handlers
  "Like comp, but composes a number of traverse handlers (functions
  of signature `[context options] => list-of-contexts`) that are
  expected to be synchronous."
  ([h] h)
  ([h1 h2]
   (fn [context options]
     (h1 (first (h2 context options)) options)))
  ([h1 h2 & hs]
   (compose-sync-handlers h1 (apply compose-sync-handlers h2 hs))))

(defn- make-squashed-handler
  "Composes together all handlers in the pipeline."
  [pipeline]
  (apply compose-sync-handlers
         (map (partial ns-resolve *ns*) (reverse pipeline))))

(defn- advance-pipeline
  "Advances `context` to the next stage in `pipeline`."
  [pipeline context]
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

(defn- init-handler
  "Sets up context with `::current-processor` and `::cache-key`."
  [context options]
  (let [{:keys [cache-template cache-key-fn]} (merge options (@processors (:processor context)))
        cache-key-fn (or cache-key-fn
                         #(when cache-template
                           (format-template cache-template %)))]
    [(assoc context
            ::current-processor (@processors (:processor context))
            ::cache-key (cache-key-fn context))]))

(defn- updatable?
  "Should we redownload the given context even if we have it cached?"
  [context]
  (let [updatable (get-in context [::current-processor :updatable])]
    (if (fn? updatable)
      (updatable context)
      updatable)))

;; TODO: deduplicate code around here
(defn- maybe-retrieve-from-http-cache
  "When a context's cache-key exists in the cache, fetches the associated
  data."
  [context options]
  (when (or (not (:update options))
            (not (updatable? context)))
    (if-let [key (::cache-key context)]
      (if-let [item (cache/load-blob (:html-cache options) key)]
        {:body (:blob item), :headers (:meta item)}))))

(defn- maybe-retrieve-from-processed-cache
  "Likewise, for processed cache."
  [context options]
  (when (or (not (:update options))
            (not (updatable? context)))
    (if-let [key (::cache-key context)]
      (if-let [item (cache/load-blob (:processed-cache options) key)]
        (edn/read-string (String. (:blob item)))))))

(defn- check-cache-handler
  "If context is cached, loads the cached data and skips [[download-handler]],
  otherwise returns it as-is."
  [context options]
  (let [processed-result (maybe-retrieve-from-processed-cache context options)
        cached-response (maybe-retrieve-from-http-cache context options)]
    (cond
      (and (:uncached-only options) (or processed-result cached-response))
      #_=> []
      processed-result
      #_=> [(assoc context
                   ::new-items (map (partial merge-contexts context) processed-result)
                   ::next-stage `split-handler)]
      cached-response
      #_=> [(assoc context
                   ::response cached-response
                   ::next-stage `process-handler)]
      :otherwise
      #_=> [context])))

(defn- wait
  "If ms-or-fn is a number, Thread/sleep that many milliseconds, otherwise
  assume that it's a zero-argument function, call it and sleep for the resulting
  number."
  [ms-or-fn]
  (when ms-or-fn
    (let [ms (if (number? ms-or-fn)
               ms-or-fn
               (ms-or-fn))]
      (Thread/sleep ms))))

(defn signal-error
  "Call this function from `download-error-handler` to cause scraping to signal an error."
  [error context]
  [{:skyscraper.traverse/error error,
    :skyscraper.traverse/context context}])

(defn respond-with
  "Call this function from `download-error-handler` to continue scraping as if download had succeeded."
  [response {:keys [pipeline] :as options} context]
  [(cond-> (advance-pipeline pipeline context)
     true (assoc ::response response)
     (:cookies response) (update :http/cookies merge (:cookies response)))])

(defn default-download-error-handler
  "By default, when clj-http returns an error (e.g., when the server returns 4xx or 5xx),
  Skyscraper will call this function to determine what to do next.
  This handler causes Skyscraper to retry up to `retries` times for 5xx status codes,
  and to throw an exception otherwise."
  [error options context]
  (let [{:keys [status]} (ex-data error)
        retry? (and status (>= status 500))
        retry (inc (or (::retry context) 0))]
    (if (and retry? (< retry (:retries options)))
      (do
        (warnf "[download] Unexpected error %s, retry %s, context %s" error retry (context/describe context))
        [(assoc context ::retry retry)])
      (do
        (warnf "[download] Unexpected error %s, giving up, context %s" error (context/describe context))
        (signal-error error context)))))

(defn- download-handler
  "Asynchronously downloads the page specified by context."
  [context {:keys [connection-manager download-semaphore retries sleep] :as options} callback]
  (debugf "Running download-handler: %s" (:processor context))
  (let [req (merge {:method :get, :url (:url context)}
                   (extract-namespaced-keys "http" context))
        success-fn (fn [response]
                     (debugf "[download] Downloaded %s" (:url context))
                     (.release download-semaphore)
                     (callback (respond-with response options context)))
        error-fn (fn [error]
                   (.release download-semaphore)
                   (let [error-handler (:download-error-handler options)]
                     (callback (error-handler error options context))))]
    (debugf "[download] Waiting")
    (.acquire download-semaphore)
    (infof "[download] Downloading %s" (:url context))
    (let [req (merge {:async? true,
                      :connection-manager connection-manager}
                     req (get-option context options :http-options))
          request-fn (or (:request-fn options)
                         http/request)]
      (wait sleep)
      (request-fn req
                  success-fn
                  error-fn))))

(defn- sync-download-handler
  "Synchronous version of download-handler that also checks for cache."
  [context {:keys [pipeline connection-manager] :as options}]
  (let [req (merge {:method :get, :url (:url context), :connection-manager connection-manager}
                   (extract-namespaced-keys "http" context)
                   (get-option context options :http-options))
        request-fn (or (:request-fn options)
                       http/request)]
    (try
      (infof "[download] Downloading %s" (:url context))
      (let [cached (maybe-retrieve-from-http-cache context options)
            resp (or cached (request-fn req))]
        (debugf "[download] %s %s" (if cached "Retrieved from cache:" "Downloaded:") (:url context))
        [(cond-> context
           true (assoc ::response resp)
           (:cookies resp) (update :http/cookies merge (:cookies resp)))])
      (catch Exception error
        (let [error-handler (:download-error-handler options)]
          (error-handler error options context))))))

(defn- store-cache-handler
  "Assuming context has downloaded data, stores it in HTML cache if
  applicable and returns it unmodified."
  [context options]
  (when-let [key (::cache-key context)]
    (cache/save-blob (:html-cache options) key (get-in context [::response :body]) (get-in context [::response :headers])))
  [context])

(defn- process-handler
  "Runs the processor specified by context on itself. Returns a single context
  with the processor results as `::new-items`."
  [context options]
  (let [parse (get-option context options :parse-fn)
        {:keys [headers body]} (::response context)
        document (parse (into (http-headers/header-map) headers) body context)
        processor-name (:processor context)
        result (run-processor processor-name document context)]
    (cache/save-blob (:processed-cache options) (::cache-key context) (.getBytes (pr-str result)) nil)
    [(assoc context ::new-items (map (partial merge-contexts context) result))]))

(defn- split-handler
  "Extracts `::new-items` out of the supplied contexts and prunes the scraping
  tree if necessary."
  [context options]
  (->> (::new-items context)
       (map #(assoc % ::stage `split-handler))
       (filter-contexts options)))

(defn- sync-split-handler
  "A sync version of [[split-handler]]."
  [context options]
  (->> (::new-items context)
       (filter-contexts options)
       (map #(if (and (:processor %) (:url %))
               %
               (context/dissoc-leaf-keys %)))))

(defn- sync-handler [context options]
  "A handler that runs the squashed pipeline."
  (debugf "Running sync-handler: %s %s" (::stage context) (:processor context))
  (let [f (ns-resolve *ns* (::stage context))
        results (f context options)]
    (map (partial advance-pipeline (:pipeline options)) results)))

(defn initialize-seed
  "Ensures the seed is a seq and sets up internal keys."
  [{:keys [download-mode pipeline] :as options} seed]
  (let [seed (ensure-distinct-seq seed)]
    (case download-mode
      :async (mapv #(advance-pipeline pipeline %) seed)
      :sync (mapv #(assoc %
                          ::traverse/call-protocol :sync
                          ::traverse/handler (make-squashed-handler pipeline))
                  seed))))

(def default-options
  "Default scraping options."
  {:max-connections 10,
   :retries 5,
   :conn-mgr-options {},
   :parse-fn parse-enlive,
   :download-mode :async,
   :download-error-handler default-download-error-handler,
   :http-options {:redirect-strategy :lax,
                  :as :byte-array,
                  :socket-timeout 30000,
                  :connection-timeout 30000}})

(defn initialize-options
  "Initializes scraping options, ensuring that the caches are
  instances of [[CacheBackend]], and a db is present if `:db-file`
  was supplied."
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

(defn scrape
  "Runs scraping on seed (an initial context or sequence of contexts), returning
  a lazy sequence of leaf contexts.

  `options` may include the ones supported by [[skyscraper.traverse/launch]],
  as well as:

  - `:conn-mgr-options` – Skyscraper will create a clj-http connection manager
    with these options (a sync or async one, depending on `:download-mode`)
    and use it across all HTTP requests it makes.
    See [[clj-http.conn-mgr/make-reusable-conn-manager]] and
    [[clj-http.conn-mgr/make-reusable-async-conn-manager]] for details on the
    options you can specify here.
  - `:db` – a clojure.java.jdbc compatible db-spec that, when passed, will
    cause scraping to generate a SQL database of results. See
    `doc/db.md` for a walkthrough. Only supports SQLite.
  - `:db-file` – an alternative to `:db`, a filename or path that will
    be used to construct a SQLite db-spec.
  - `:download-error-handler` – a function called when clj-http returns an
    error when downloading; see `doc/error-handling.md` for details.
  - `:download-mode` – can be `:async` (default) or `:sync`. When async,
    Skyscraper will use clj-http's asynchronous mode to make HTTP requests.
  - `:html-cache` – the HTTP cache to use. Can be an instance of `CacheBackend`,
    a string (meaning a directory to use for a filesystem cache), `nil` or `false`
    (meaning no cache), or `true` (meaning a filesystem cache in the default
    location, [[html-cache-dir]]). Defaults to `nil`.
  - `:http-options` – a map of additional options that will be passed to
    [[clj-http.core/request]].
  - `:max-connections` – maximum number of HTTP requests that can be active
    at any time.
  - `:only` – prunes the scrape tree to only include matching contexts; this can be
    a map (specifying to only include records whose values, if present, coincide with
    the map) or a predicate (meaning to filter contexts on it).
  - `:parse-fn` – a function that takes a map of HTTP headers and a byte array
    containing the downloaded document, and returns a parsed representation of
    that document. Skyscraper provides [[parse-string]], [[parse-enlive]], and
    [[parse-reaver]] out of the box. Defaults to [[parse-enlive]].
  - `:processed-cache` – the processed cache to use. Same possible values as
    for `:http-cache`. Defaults to `nil`.
  - `:request-fn` – the HTTP request function to use. Defaults to [[clj-http.core/request]].
    Skyscraper relies on the API of clj-http, so only override this if you
    know what you're doing.
  - `:retries` – maximum number of times that Skyscraper will retry downloading
    a page until it gives up. Defaults to 5.
  - `:sleep` – sleep this many milliseconds before each request, or a niladic fn
    that returns a number of milliseconds. Useful for throttling. It's probably
    best to set `:parallelism` to 1 together with this.
  - `:uncached-only` – prune the scrape tree, yielding only the nodes that haven't been
    scraped yet. See `doc/updates.md`.
  - `:update` – run in update mode (see `doc/updates.md`)."
  [seed & {:as options}]
  (let [options (initialize-options options)
        seed (initialize-seed options seed)]
    (traverse/leaf-seq seed options)))

(defn scrape!
  "Like scrape, but eager: terminates after scraping has succeeded. Returns nil.
  Pass `:db`, `:db-file`, `:leaf-chan`, or `:item-chan` to access scraped data.

  `options` are the same as in `scrape!`."
  [seed & {:as options}]
  (let [options (initialize-options options)
        seed (initialize-seed options seed)]
    (traverse/traverse! seed options)))

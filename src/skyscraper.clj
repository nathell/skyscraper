 (ns skyscraper
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure-csv.core :as csv]
            [net.cgrand.enlive-html :refer [html-resource select attr? text emit* has pred first-child last-child]]))

;;; Directories

(def output-dir
  "All Skyscraper output, either temporary or final, goes under here."
  (str (System/getProperty "user.home") "/skyscraper-data/"))

(def html-cache-dir
  "Local copies of downloaded HTML files go here."
  (str output-dir "cache/html/"))

(def cache-dir
  "Cache storing the interim results of processing HTML files."
  (str output-dir "cache/processed/"))

;;; Utility functions

(defn save
  "Saves a Clojure datum in a file named by name, creating its parent
   directories if necessary. The datum can be read back by read or
   clojure.edn/read."
  [name datum]
  (io/make-parents name)
  (with-open [f (io/writer name)]
    (binding [*out* f *print-length* nil *print-level* nil]
      (prn datum))))

(let [mutex (Object.)]
  (defn log
    "Formats and logs a message to *out*. Thread-safe."
    [& args]
    (let [s (apply format args)]
      (locking mutex
        (println s)
        (flush)))))

;;; URL manipulation

(defn merge-urls
  "Fills the missing parts of new-url (which can be either absolute,
   root-relative, or relative) with corresponding parts from url
   (an absolute URL) to produce a new absolute URL."
  [url new-url]
  (let [[_ proto _ hostname path] (re-find #"^(https?)?(://)?([^/]+)(/.*)?$" url)
        proto (or proto "http")
        path (or path "/")]
    (cond
     (re-find #"^https?://" new-url) new-url
     (.startsWith new-url "//") (str proto ":" new-url)
     (.startsWith new-url "/") (str proto "://" hostname new-url)
     (.endsWith path "/") (str proto "://" hostname path new-url)
     :otherwise (str proto "://" hostname path "/" new-url))))

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

;;; Downloading

(defn resource
  "A wrapper for html-resource that allows to specify an encoding (defaults to ISO-8859-2)."
  ([f] (resource f nil))
  ([f encoding] (html-resource (io/reader f :encoding (or encoding "ISO-8859-2")))))

(defn download
  "If a file named by local-path exists in html-cache-dir, returns it
  as a File object.  Otherwise, downloads a file from the given URL,
  stores it in the cache and returns the cached File object."
  ([url local-path] (download url local-path 5))
  ([url local-path retries]
   (let [local-name (str html-cache-dir local-path ".html")
         f (io/file local-name)]
     (when-not (.exists f)
       (do
         (when (zero? retries)
           (throw (Exception. (str "Maximum number of retries exceeded: " url))))
         (log "Downloading %s -> %s" url local-name)
         (try
           (io/make-parents local-name)
           (with-open [in #_(:body @(http/get url {:as :stream, :timeout 30000})) (io/input-stream url)
                       out (io/output-stream local-name)]
             (io/copy in out))
           (catch Exception e
             (log "Exception while trying to download %s, retrying: %s" url e)
             (.delete f)
             (download url local-path (dec retries))))))
     f)))

;;; Processors

(defn processor
  "Performs a single stage of scraping."
  [input-context
   {:keys [input-cache]
    :or {input-cache true}}
   &
   {:keys [url-fn local-cache-fn cache-template process-fn encoding]
    :or {url-fn :url, encoding "UTF-8"}}]
  (let [local-cache-fn (or local-cache-fn #(format-template cache-template %))
        cache-name (local-cache-fn input-context)
        cache-file (io/file (str cache-dir cache-name ".edn"))]
    (if (and input-cache (.exists cache-file))
      (read-string (slurp cache-file))
      (let [url (url-fn input-context)
            res (resource (download url cache-name) encoding)
            processed (->> res
                           process-fn
                           (map #(if (:url %) (update-in % [:url] (partial merge-urls url)) %))
                           vec)]
        (save cache-file processed)
        processed))))

(defmacro defprocessor
  [name & opts]
  `(defn ~name [context# user-opts#]
     (processor context# user-opts# ~@opts)))

;;; Scraping engine

(defn do-scrape
  [data params]
  (mapcat (fn [x]
            (if-let [processor-key (:processor x)]
              (let [proc (resolve (symbol (name processor-key)))
                    input-context (dissoc x :processor)
                    res (proc input-context params)
                    res (map (partial into input-context) res)]
                (do-scrape res params))
              [x]))
          data))

(defn scrape
  [data & {:as params}]
  (do-scrape data params))

(defn separate
  [pred coll]
  [(take-while pred coll) (drop-while pred coll)])

(defn uncompress-tree
  ([tree] (uncompress-tree [] tree))
  ([path tree]
   (let [[node subtrees] (separate (comp not vector?) tree)
         path (into path node)]
     (if (seq subtrees)
       (mapcat (partial uncompress-tree path) subtrees)
       [path]))))

;;; Enlive helpers

(defn href
  [x]
  (cond
   (nil? x) nil
   (and (map? x) (= :a (:tag x))) (-> x :attrs :href)
   :otherwise (href (first (select x [:a])))))

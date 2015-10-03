;;;; Skyscraper - Core library

(ns skyscraper
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure.data.csv :as csv]
            [clj-http.client :as http]
            [skyscraper.cache :as cache]
            [net.cgrand.enlive-html :refer [html-resource select]])
  (:import java.net.URL))

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
  (str (URL. (URL. url) new-url)))

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

(defn string-resource
  "Returns an Enlive resource for a HTML snippet passed as a string."
  [s]
  (html-resource (java.io.StringReader. s)))

(defn download
  "If a file named by local-path exists in the HTML cache, returns its
  content as a string.  Otherwise, downloads a file from the given URL,
  stores it in the cache and returns the cached file's textual content.
  Passes options to clj-http."
  [url local-path html-cache force options retries]
  (or
   (and (not force) (cache/load-string html-cache local-path))
   (do
     (when (zero? retries)
       (throw (Exception. (str "Maximum number of retries exceeded: " url))))
     (log "Downloading %s -> %s" url local-path)
     (try
       (let [html (:body (http/get url (into {:as :auto, :socket-timeout 5000, :decode-body-headers true} options)))]
         (cache/save-string html-cache local-path html)
         html)
       (catch Exception e
         (log "Exception while trying to download %s, retrying: %s" url e)
         (download url local-path html-cache force options (dec retries)))))))

;;; Processors

(defn ensure-seq
  "Returns the argument verbatim if it's a map. Otherwise, wraps it in a vector."
  [x]
  (if (map? x) [x] x))

(defn sanitize-cache
  "Converts a cache argument to the processor to a CacheBackend if it
   isn't one already."
  [value cache-dir]
  (cond
   (= value true) (cache/fs cache-dir)
   (not value) (cache/null)
   :otherwise value))

(defn processor
  "Performs a single stage of scraping."
  [input-context
   {:keys [html-cache processed-cache update http-options retries]
    :or {html-cache true, processed-cache true, update false, http-options nil, retries 5}}
   &
   {:keys [url-fn cache-key-fn cache-template process-fn updatable]
    :or {url-fn :url}}]
  (let [html-cache (sanitize-cache html-cache html-cache-dir)
        processed-cache (sanitize-cache processed-cache processed-cache-dir)
        cache-key-fn (or cache-key-fn #(format-template cache-template %))
        cache-key (cache-key-fn input-context)
        force (and update updatable)]
    (or
      (when-not force
        (cache/load processed-cache cache-key))
      (let [url (url-fn input-context)
            input-context (assoc input-context :url url)
            src (download url cache-key html-cache force http-options retries)
            res (string-resource src)
            processed (->> (process-fn res input-context)
                           ensure-seq
                           (map #(if (:url %) (update-in % [:url] (partial merge-urls url)) %))
                           vec)]
        (cache/save processed-cache cache-key processed)
        processed))))

(defmacro defprocessor
  [name & opts]
  `(defn ~name [context# user-opts#]
     (processor context# user-opts# ~@opts)))

;;; Scraping engine

;; From http://stackoverflow.com/questions/3407876
(defn unchunk [s]
  (when (seq s)
    (lazy-seq
      (cons (first s)
            (unchunk (next s))))))

;; From http://stackoverflow.com/questions/21943577/mapcat-breaking-the-lazyness
(defn my-mapcat
  "Like mapcat, but fully lazy."
  [f coll]
  (lazy-seq
   (if (not-empty coll)
     (concat
      (f (first coll))
      (my-mapcat f (rest coll))))))

(defn do-scrape
  [data params]
  (my-mapcat (fn [x]
               (if-let [processor-key (:processor x)]
                 (let [proc (ns-resolve (symbol (or (namespace processor-key) (str *ns*))) (symbol (name processor-key)))
                       input-context (dissoc x :processor)
                       res (unchunk (proc input-context params))
                       res (map (partial into input-context) res)]
                   (do-scrape res params))
                 (list (dissoc x :url))))
             data))

(defn scrape
  [data & {:as params}]
  (do-scrape data params))

(defn scrape-csv
  [data output & {:as params}]
  (let [ks (vec (reduce into #{} (map keys (do-scrape data params))))]
    (with-open [f (io/writer output)]
      (csv/write-csv f [(map name ks)])
      (csv/write-csv f (map (fn [row-data] (map (comp str row-data) ks)) (do-scrape data (assoc params :update false)))))))

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

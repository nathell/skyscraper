(ns skyscraper
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure-csv.core :as csv]
            [clj-http.client :as http]
            [net.cgrand.enlive-html :refer [html-resource select]])
  (:import java.net.URL))

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

(defn resource
  "A wrapper for html-resource that allows to specify an encoding (defaults to ISO-8859-2)."
  ([f] (resource f nil))
  ([f encoding] (html-resource (io/reader f :encoding (or encoding "ISO-8859-2")))))

(defn download
  "If a file named by local-path exists in html-cache-dir, returns it
  as a File object.  Otherwise, downloads a file from the given URL,
  stores it in the cache and returns the cached File object. Passes
  options to clj-http."
  ([url local-path options] (download url local-path options 5))
  ([url local-path options retries]
   (let [local-name (str html-cache-dir local-path ".html")
         f (io/file local-name)]
     (when-not (.exists f)
       (do
         (when (zero? retries)
           (throw (Exception. (str "Maximum number of retries exceeded: " url))))
         (log "Downloading %s -> %s" url local-name)
         (try
           (io/make-parents local-name)
           (let [content (:body (http/get url (into {:as :auto, :socket-timeout 5000, :decode-body-headers true} options)))]
             (spit local-name content))
           (catch Exception e
             (log "Exception while trying to download %s, retrying: %s" url e)
             (.delete f)
             (download url local-path options (dec retries))))))
     f)))

;;; Processors

(defn processor
  "Performs a single stage of scraping."
  [input-context
   {:keys [processed-cache http-options]
    :or {processed-cache true, http-options nil}}
   &
   {:keys [url-fn local-cache-fn cache-template process-fn encoding]
    :or {url-fn :url, encoding "UTF-8"}}]
  (let [local-cache-fn (or local-cache-fn #(format-template cache-template %))
        cache-name (local-cache-fn input-context)
        cache-file (io/file (str cache-dir cache-name ".edn"))]
    (if (and processed-cache (.exists cache-file))
      (read-string (slurp cache-file))
      (let [url (url-fn input-context)
            res (resource (download url cache-name http-options) encoding)
            processed (->> (process-fn res input-context)
                           (map #(if (:url %) (update-in % [:url] (partial merge-urls url)) %))
                           vec)]
        (save cache-file processed)
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

;; From CLJ-1218
(defn join
  "Lazily concatenates a sequence-of-sequences into a flat sequence."
  [s]
  (lazy-seq
   (when-let [s (seq s)] 
     (concat (first s) (join (rest s))))))

(defn do-scrape
  [data params]
  (join (map (fn [x]
               (if-let [processor-key (:processor x)]
                 (let [proc (resolve (symbol (name processor-key)))
                       input-context (dissoc x :processor)
                       res (unchunk (proc input-context params))
                       res (map (partial into input-context) res)]
                   (do-scrape res params))
                 (list (dissoc x :url))))
              data)))

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

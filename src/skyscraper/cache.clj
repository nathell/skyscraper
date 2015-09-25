;;;; Skyscraper - Cache backends

(ns skyscraper.cache
  (:refer-clojure :exclude [load load-string])
  (:require [clojure.java.io :as io]))

(defprotocol CacheBackend
  "Provides facilities for caching HTML and arbitrary Clojure maps in
   some kind of storage. Implementations of this protocol can be passed
   as :html-cache and :processed-cache options to `skyscraper/scrape`."
  (save-string [cache key string]
    "Saves a string under a given key to the cache.")
  (save [cache key value]
    "Saves an arbitrary Clojure value (actually a map) under a given
    key to the cache.")
  (load-string [cache key]
    "Loads and returns a string corresponding to the given key. If the
     cache didn't contain the key, returns nil, telling Skyscraper to
     redownload it.")
  (load [cache key]
    "Loads and returns a map corresponding to given key. If the cache
     didn't contain the key, returns nil, telling Skyscraper to
     recompute the value. It is okay if the returned map is of another
     type than the one saved originally, as long as they both implement
     IPersistentMap with the same contents."))

;; An implementation of CacheBackend that stores the strings and values
;; in a filesystem under a specific directory (root-dir). The file names
;; correspond to the stored keys, with .html or .edn extensions as
;; appropriate. root-dir must end in a path separator (/).
(deftype FSCache
    [root-dir]
  CacheBackend
  (save-string [cache key string]
    (let [file (str root-dir key ".html")]
      (io/make-parents file)
      (spit file string)))
  (save [cache key value]
    (let [file (str root-dir key ".edn")]
      (io/make-parents file)
      (with-open [f (io/writer file)]
        (binding [*out* f *print-length* nil *print-level* nil]
          (prn value)))))
  (load-string [cache key]
    (let [f (io/file (str root-dir key ".html"))]
      (when (.exists f)
        (slurp f))))
  (load [cache key]
    (let [f (io/file (str root-dir key ".edn"))]
      (when (.exists f)
        (read-string (slurp f))))))

(defn fs
  "Creates a filesystem-based cache backend with a given root directory."
  [root-dir]
  (FSCache. (str root-dir "/")))

;; A dummy implementation of CacheBackend that doesn't actually cache data.
(deftype NullCache
    []
  CacheBackend
  (save-string [_ _ _] nil)
  (save [_ _ _] nil)
  (load-string [_ _] nil)
  (load [_ _] nil))

(defn null []
  "Creates a null cache backend."
  (NullCache.))

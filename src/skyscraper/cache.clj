;;;; Skyscraper - Cache backends

(ns skyscraper.cache
  (:refer-clojure :exclude [load load-string])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.io InputStream OutputStream]))

;;; Netstrings

(let [bytes-type (type (byte-array []))]
  (defn- byte-array? [item]
    (= (type item) bytes-type)))

(defn- write-netstring
  [^OutputStream stream item]
  (let [^bytes b (cond (byte-array? item) item
                       (string? item)     (.getBytes item)
                       :otherwise         (.getBytes (pr-str item)))]
    (.write stream (.getBytes (str (count b))))
    (.write stream (int \:))
    (.write stream b)
    (.write stream (int \,))))

(defn- read-netstring
  [^InputStream stream]
  (loop [len 0]
    (let [ch (.read stream)]
      (cond (<= 48 ch 57) (recur (+ (* 10 len) ch -48))
            (= ch 58) (let [arr (byte-array len)]
                        (.read stream arr)
                        (assert (= (.read stream) 44))
                        arr)
            :otherwise (throw (Exception. "colon needed after length"))))))

;;; Actual cache

(defprotocol CacheBackend
  "Provides facilities for caching downloaded blobs (typically HTML),
   potentially enriched with some metadata (typically headers), in
   some kind of storage. Implementations of this protocol can be passed
   as :html-cache and :processed-cache options to `skyscraper.core/scrape`."
  (save-blob [cache key blob metadata])
  (load-blob [cache key]))

;; An implementation of CacheBackend that stores the blobs in a
;; filesystem under a specific directory (root-dir).  The blobs are
;; stored as netstrings (http://cr.yp.to/proto/netstrings.txt),
;; prefixed with metadata EDN also stored as a netstring. The
;; filenames correspond to the stored keys. root-dir must end in a
;; path separator (/).
(deftype FSCache
    [root-dir]
  CacheBackend
  (save-blob [cache key blob metadata]
    (let [meta-str (pr-str metadata)
          file (str root-dir key)]
      (io/make-parents file)
      (with-open [f (io/output-stream file)]
        (write-netstring f metadata)
        (write-netstring f blob))))
  (load-blob [cache key]
    (try
      (with-open [f (io/input-stream (str root-dir key))]
        (let [meta-blob (read-netstring f)
              blob (read-netstring f)]
          {:meta (edn/read-string (String. meta-blob))
           :blob blob}))
      (catch Exception _ nil))))

(defn fs
  "Creates a filesystem-based cache backend with a given root directory."
  [root-dir]
  (FSCache. (str root-dir "/")))

;; A dummy implementation of CacheBackend that doesn't actually cache data.
(deftype NullCache
    []
  CacheBackend
  (save-blob [_ _ _ _] nil)
  (load-blob [_ _] nil))

(defn null
  "Creates a null cache backend."
  []
  (NullCache.))

(extend-protocol CacheBackend
  nil
  (save-blob [_ _ _ _] nil)
  (load-blob [_ _] nil))

;; An in-memory implementation of CacheBackend backed by two atoms.
(deftype MemoryCache
    [storage]
  CacheBackend
  (save-blob [cache key blob metadata]
    (swap! storage assoc key {:blob blob, :meta metadata}))
  (load-blob [cache key]
    (@storage key)))

(defn memory
  "Creates a memory cache backend."
  []
  (MemoryCache. (atom {})))

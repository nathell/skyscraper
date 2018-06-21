(ns skyscraper.async
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as impl])
  (:import [java.util LinkedList]))

(deftype LIFOBuffer [^LinkedList buf ^long n]
  impl/Buffer
  (full? [this]
    (>= (.size buf) n))
  (remove! [this]
    (.removeFirst buf))
  (add!* [this itm]
    (.addFirst buf itm)
    this)
  (close-buf! [this])
  clojure.lang.Counted
  (count [this]
    (.size buf)))

(defn lifo-buffer [^long n]
  (LIFOBuffer. (LinkedList.) n))

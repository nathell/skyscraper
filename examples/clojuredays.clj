(ns clojuredays
  (:require [reaver]
            [skyscraper.core :as sky]
            [skyscraper.cache :as sky.cache]
            [taoensso.carmine :as car]))

; Redis setup as CacheBackend
; this example assumes you have a local Redis instance running

; tested with
; deps.edn:
; com.taoensso/carmine {:mvn/version "3.2.0-alpha1"}
; reaver/reaver {:mvn/version "0.1.3"}

(def redis-conn {:pool {} :spec {:uri "redis://127.0.0.1:6379"}})
(defmacro wcar* [& body] `(car/wcar redis-conn ~@body))

(defn redis-cache
  []
  (reify
    sky.cache/CacheBackend
    (save-blob [_ key blob metadata]
      (wcar*
       (car/set key {:blob blob
                     :meta metadata})))
    (load-blob [_ key]
      (wcar*
       (car/get key)))
    java.io.Closeable
    (close [_]
      nil)))

(def seed
  [{:url "https://clojuredays.org",
    :processor :editions
    :page :index}])

(sky/defprocessor :editions
  :cache-template "dcd/:page"
  :skyscraper.db/columns [:edition]
  :skyscraper.db/key-columns [:edition]
  :process-fn (fn [doc _ctx]
                (reaver/extract-from doc
                                     "aside > a.item" ; or "#sidebar > a.item"
                                     [:edition :url :processor]
                                     ".item" reaver/text
                                     ".item" (reaver/attr :href)
                                     ".item" (constantly :sponsors))))

(sky/defprocessor :sponsors
  :cache-template "dcd/:edition"
  :skyscraper.db/columns [:sponsor_url :sponsor_name]
  :skyscraper.db/key-columns [:sponsor_url :sponsor_name]
  :process-fn (fn [doc _ctx]
                (reaver/extract-from doc
                                     ".sponsors > a" ; "#sponsors .sponsors > a"
                                     [:sponsor_url :sponsor_name]
                                     ".sponsor" (reaver/attr :href)
                                     ".sponsor > img" (reaver/attr :alt))))

(defn run [cache]
  (sky/scrape! seed
               :html-cache cache
               :db-file "/tmp/dcd.db"
               :parse-fn sky/parse-reaver))

(run (redis-cache))
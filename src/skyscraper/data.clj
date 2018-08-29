(ns skyscraper.data)

(defn separate [f s]
  [(filter f s) (filter (complement f) s)])

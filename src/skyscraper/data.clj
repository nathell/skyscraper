(ns skyscraper.data
  "Internal namespace for helper functions that deal with data.")

(defn separate
  "Splits s into elements that satisfy f and ones that don't."
  [f s]
  [(filter f s) (filter (complement f) s)])

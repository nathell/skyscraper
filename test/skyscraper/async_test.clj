(ns skyscraper.async-test
  (:require
    [clojure.test :refer [deftest]]
    [skyscraper.async :as async]))

(defn xform [{:keys [number]}]
  (filter (comp pos? :number)
          (map #(let [n (+ (* 10 number) %)]
                  (merge {:number n}
                         (when (< n 100)
                           {:skyscraper/processor xform, :skyscraper/call-protocol :sync})))
               (range 10))))

(deftest test-async
  (async/launch [{:number 0, :skyscraper/processor xform, :skyscraper/call-protocol :sync}]
                {}))

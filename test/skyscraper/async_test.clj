(ns skyscraper.async-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [skyscraper.async :as async]
    [taoensso.timbre :as timbre]))

(defn xform [{:keys [number]}]
  (filter (comp pos? :number)
          (map #(let [n (+ (* 10 number) %)]
                  (merge {:number n}
                         (when (< n 100)
                           {:skyscraper/processor xform, :skyscraper/call-protocol :sync})))
               (range 10))))

(defn xform-async [context on-done]
  (Thread/sleep (rand-int 1000))
  (on-done (map #(if (:skyscraper/processor %)
                   (assoc %
                          :skyscraper/processor xform-async
                          :skyscraper/call-protocol :callback)
                   %)
                (xform context))))

(deftest test-process
  (async/process! [{:number 0, :skyscraper/processor xform, :skyscraper/call-protocol :sync}] {}))

(deftest test-process-as-seq
  (timbre/set-level! :info)
  (doseq [p [1 4 16 128]]
    (testing (str "parallelism " p)
      (testing "synchronous calls"
        (let [items (async/process-as-seq [{:number 0, :skyscraper/processor xform, :skyscraper/call-protocol :sync}] {:parallelism p})
              numbers (map :number items)]
          (is (= (sort numbers) (range 100 1000)))))
      (testing "async calls"
        (let [items (async/process-as-seq [{:number 0, :skyscraper/processor xform-async, :skyscraper/call-protocol :callback}] {:parallelism p})
              numbers (map :number items)]
          (is (= (sort numbers) (range 100 1000))))))))

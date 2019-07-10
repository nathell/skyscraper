(ns skyscraper.async-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [skyscraper.async :as async]
    [taoensso.timbre :as timbre]))

(defn xform [next-fn {:keys [number]}]
  (filter (comp pos? :number)
          (map #(let [n (+ (* 10 number) %)]
                  (merge {:number n, :skyscraper/priority (- n)}
                         (when (< n 100)
                           (next-fn))))
               (range 10))))

(defn xform-sync [context]
  (xform (constantly {:skyscraper/processor xform-sync, :skyscraper/call-protocol :sync}) context))

(defn xform-async [context on-done]
  (Thread/sleep (rand-int 1000))
  (on-done (xform (constantly {:skyscraper/processor xform-async, :skyscraper/call-protocol :callback}) context)))

(declare xform-async-random)

(defn xform-sync-random [context]
  (xform #(rand-nth [{:skyscraper/processor xform-sync-random, :skyscraper/call-protocol :sync}
                     {:skyscraper/processor xform-async-random, :skyscraper/call-protocol :callback}])
         context))

(defn xform-async-random [context on-done]
  (Thread/sleep (rand-int 1000))
  (on-done
   (xform #(rand-nth [{:skyscraper/processor xform-sync-random, :skyscraper/call-protocol :sync}
                      {:skyscraper/processor xform-async-random, :skyscraper/call-protocol :callback}])
          context)))

(deftest test-process
  (async/process! [{:number 0, :skyscraper/processor xform-sync, :skyscraper/call-protocol :sync}] {}))

(deftest test-process-as-seq
  (timbre/set-level! :info)
  (doseq [p [1 4 16 128]]
    (testing (str "parallelism " p)
      (doseq [[call-type processor protocol] [["sync" xform-sync :sync] ["async" xform-async :callback] ["mixed" xform-sync-random :sync]]]
        (testing (str call-type " calls")
          (let [items (async/process-as-seq [{:number 0, :skyscraper/processor processor, :skyscraper/call-protocol protocol}] {:parallelism p})
                numbers (map :number items)]
            (is (= (sort numbers) (range 100 1000)))))))))

(deftest test-priority
  (let [items (async/process-as-seq [{:number 0, :skyscraper/processor xform-sync, :skyscraper/call-protocol :sync}]
                                    {:parallelism 1, :prioritize? true})
        numbers (map :number items)]
    (is (= numbers (for [x (range 99 9 -1) y (range 10)] (+ (* 10 x) y))))))

(ns skyscraper.async-test
  (:require
    [clojure.core.async :as async]
    [clojure.set :as set]
    [clojure.test :refer [deftest is testing]]
    [skyscraper.async :as sasync]
    [taoensso.timbre :as timbre]))

(defn xform [next-fn {:keys [number]}]
  (filter (comp pos? :number)
          (map #(let [n (+ (* 10 number) %)]
                  (merge {:number n, ::sasync/priority (- n)}
                         (when (< n 100)
                           (next-fn))))
               (range 10))))

(defn xform-sync [context]
  (xform (constantly {::sasync/processor xform-sync, ::sasync/call-protocol :sync}) context))

(defn xform-async [context on-done]
  (Thread/sleep (rand-int 1000))
  (on-done (xform (constantly {::sasync/processor xform-async, ::sasync/call-protocol :callback}) context)))

(defn xform-erroring [{:keys [number] :as context}]
  (if (= number 5)
    (throw (Exception. "Five is right out!"))
    (xform (constantly {::sasync/processor xform-erroring, ::sasync/call-protocol :sync}) context)))

(declare xform-async-random)

(defn xform-sync-random [context]
  (xform #(rand-nth [{::sasync/processor xform-sync-random, ::sasync/call-protocol :sync}
                     {::sasync/processor xform-async-random, ::sasync/call-protocol :callback}])
         context))

(defn xform-async-random [context on-done]
  (Thread/sleep (rand-int 1000))
  (on-done
   (xform #(rand-nth [{::sasync/processor xform-sync-random, ::sasync/call-protocol :sync}
                      {::sasync/processor xform-async-random, ::sasync/call-protocol :callback}])
          context)))

(timbre/set-level! :info)

(deftest test-process
  (sasync/traverse! [{:number 0, ::sasync/processor xform-sync, ::sasync/call-protocol :sync}] {}))

(deftest test-process-as-seq
  (doseq [p [1 4 16 128]]
    (testing (str "parallelism " p)
      (doseq [[call-type processor protocol] [["sync" xform-sync :sync] ["async" xform-async :callback] ["mixed" xform-sync-random :sync]]]
        (testing (str call-type " calls")
          (let [items (sasync/leaf-seq [{:number 0, ::sasync/processor processor, ::sasync/call-protocol protocol}] {:parallelism p})
                numbers (map :number items)]
            (is (= (sort numbers) (range 100 1000)))))))))

(deftest test-errors
  (let [items (sasync/leaf-seq [{:number 0, ::sasync/processor xform-erroring, ::sasync/call-protocol :sync}] {})
        numbers (remove nil? (map :number items))]
    (is (= (count (filter ::sasync/error items)) 1))
    (is (= (set numbers)
           (set/difference (set (range 100 1000))
                           (set (range 500 600)))))))

(deftest test-priority
  (let [items (sasync/leaf-seq [{:number 0, ::sasync/processor xform-sync, ::sasync/call-protocol :sync}]
                               {:parallelism 1, :prioritize? true})
        numbers (map :number items)]
    (is (= numbers (for [x (range 99 9 -1) y (range 10)] (+ (* 10 x) y))))))

(deftest test-item-chan
  (let [item-chan (async/chan)
        cnt (atom 0)
        channels (sasync/launch [{:number 0, ::sasync/processor xform-sync, ::sasync/call-protocol :sync}]
                                {:item-chan item-chan})]
    (loop []
      (let [[items _] (async/alts!! [item-chan (:terminate-chan channels)])]
        (when items
          (swap! cnt + (count items))
          (recur))))
    (sasync/close-all! channels)
    (is (= @cnt 999))))

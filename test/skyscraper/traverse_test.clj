(ns skyscraper.traverse-test
  (:require
    [clojure.core.async :as async]
    [clojure.set :as set]
    [clojure.test :refer [deftest is testing]]
    [skyscraper.traverse :as traverse]
    [taoensso.timbre :as timbre]))

(defn xform [next-fn {:keys [number]}]
  (filter (comp pos? :number)
          (map #(let [n (+ (* 10 number) %)]
                  (merge {:number n, ::traverse/priority (- n)}
                         (when (< n 100)
                           (next-fn))))
               (range 10))))

(defn xform-sync [context]
  (xform (constantly {::traverse/processor xform-sync, ::traverse/call-protocol :sync}) context))

(defn xform-async [context on-done]
  (Thread/sleep (rand-int 1000))
  (on-done (xform (constantly {::traverse/processor `xform-async, ::traverse/call-protocol :callback}) context)))

(defn xform-erroring [{:keys [number] :as context}]
  (if (= number 5)
    (throw (Exception. "Five is right out!"))
    (xform (constantly {::traverse/processor xform-erroring, ::traverse/call-protocol :sync}) context)))

(declare xform-async-random)

(defn xform-sync-random [context]
  (xform #(rand-nth [{::traverse/processor xform-sync-random, ::traverse/call-protocol :sync}
                     {::traverse/processor xform-async-random, ::traverse/call-protocol :callback}])
         context))

(defn xform-async-random [context on-done]
  (Thread/sleep (rand-int 1000))
  (on-done
   (xform #(rand-nth [{::traverse/processor xform-sync-random, ::traverse/call-protocol :sync}
                      {::traverse/processor xform-async-random, ::traverse/call-protocol :callback}])
          context)))

(timbre/set-level! :info)

(deftest test-process
  (traverse/traverse! [{:number 0, ::traverse/processor xform-sync, ::traverse/call-protocol :sync}] {}))

(deftest test-process-as-seq
  (doseq [p [1 4 16 128]]
    (testing (str "parallelism " p)
      (doseq [[call-type processor protocol] [["sync" xform-sync :sync] ["async" xform-async :callback] ["mixed" xform-sync-random :sync]]]
        (testing (str call-type " calls")
          (let [items (traverse/leaf-seq [{:number 0, ::traverse/processor processor, ::traverse/call-protocol protocol}] {:parallelism p})
                numbers (map :number items)]
            (is (= (sort numbers) (range 100 1000)))))))))

(deftest test-interrupt
  (let [items (traverse/leaf-seq [{:number 0, ::traverse/processor `xform-async, ::traverse/call-protocol :callback}] {:parallelism 2, :resume-file "/tmp/skyscraper-resume"})]
    (dorun (take 420 items))
    (.shutdownNow @#'async/thread-macro-executor)
    (alter-var-root #'async/thread-macro-executor (fn [_] (java.util.concurrent.Executors/newCachedThreadPool (clojure.core.async.impl.concurrent/counted-thread-factory "async-thread-macro-%d" true))))
    (let [items' (traverse/leaf-seq [{:number 0, ::traverse/processor `xform-async, ::traverse/call-protocol :callback}] {:parallelism 2, :resume-file "/tmp/skyscraper-resume"})]
      (is (= (count items') 480)))))

(deftest test-errors
  (let [items (traverse/leaf-seq [{:number 0, ::traverse/processor xform-erroring, ::traverse/call-protocol :sync}] {})
        numbers (remove nil? (map :number items))]
    (is (= (count (filter ::traverse/error items)) 1))
    (is (= (set numbers)
           (set/difference (set (range 100 1000))
                           (set (range 500 600)))))))

(deftest test-priority
  (let [items (traverse/leaf-seq [{:number 0, ::traverse/processor xform-sync, ::traverse/call-protocol :sync}]
                                 {:parallelism 1, :prioritize? true})
        numbers (map :number items)]
    (is (= numbers (for [x (range 99 9 -1) y (range 10)] (+ (* 10 x) y))))))

(deftest test-item-chan
  (let [item-chan (async/chan)
        cnt (atom 0)
        channels (traverse/launch [{:number 0, ::traverse/processor xform-sync, ::traverse/call-protocol :sync}]
                                  {:item-chan item-chan})]
    (loop []
      (let [[items _] (async/alts!! [item-chan (:terminate-chan channels)])]
        (when items
          (swap! cnt + (count items))
          (recur))))
    (traverse/close-all! channels)
    (is (= @cnt 999))))

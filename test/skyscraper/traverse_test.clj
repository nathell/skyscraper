(ns skyscraper.traverse-test
  (:require
    [clojure.core.async :as async]
    [clojure.set :as set]
    [clojure.test :refer [deftest is testing]]
    [skyscraper.traverse :as traverse]
    [taoensso.timbre :as timbre]))

;; This is normally unused, but I enable it in dev from within tests
;; to overcome Kaocha's default behaviour of not outputting anything
;; in case a test hangs.
(defn enable-debug! []
  (timbre/set-level! :debug)
  (timbre/merge-config! {:appenders {:println {:enabled? false},
                                     :spit (taoensso.timbre.appenders.core/spit-appender {:fname "/tmp/debug.log"})}})
  (Thread/setDefaultUncaughtExceptionHandler
   (reify Thread$UncaughtExceptionHandler
     (uncaughtException [_ thread ex]
       (timbre/error ex "Uncaught exception on" (.getName thread))))))

(defn xform [next-fn {:keys [number]} options]
  (filter (comp pos? :number)
          (map #(let [n (+ (* 10 number) %)]
                  (merge {:number n, ::traverse/priority (- n)}
                         (when (< n 100)
                           (next-fn))))
               (range 10))))

(defn xform-sync [context options]
  (xform (constantly {::traverse/handler xform-sync, ::traverse/call-protocol :sync}) context options))

(defn xform-async [context options on-done]
  (Thread/sleep (rand-int 1000))
  (on-done (xform (constantly {::traverse/handler `xform-async, ::traverse/call-protocol :callback}) context options)))

(defn xform-erroring [{:keys [number] :as context} options]
  (if (= number 5)
    (throw (Exception. "Five is right out!"))
    (xform (constantly {::traverse/handler xform-erroring, ::traverse/call-protocol :sync}) context options)))

(declare xform-async-random)

(defn xform-sync-random [context options]
  (xform #(rand-nth [{::traverse/handler xform-sync-random, ::traverse/call-protocol :sync}
                     {::traverse/handler xform-async-random, ::traverse/call-protocol :callback}])
         context options))

(defn xform-async-random [context options on-done]
  (Thread/sleep (rand-int 1000))
  (on-done
   (xform #(rand-nth [{::traverse/handler xform-sync-random, ::traverse/call-protocol :sync}
                      {::traverse/handler xform-async-random, ::traverse/call-protocol :callback}])
          context options)))

(timbre/set-level! :info)

(deftest test-process
  (traverse/traverse! [{:number 0, ::traverse/handler xform-sync, ::traverse/call-protocol :sync}] {})
  (is true))

(defn enhancer
  [_ channels]
  (traverse/enhancer-loop channels identity))

(defn failing-enhancer
  [_ channels]
  (traverse/enhancer-loop
   channels
   (fn [item]
     (when (= (:number item) 123)
       (throw (ex-info "This enhancer really doesn't like that number!" {})))
     (assoc item :enhanced (+ (:number item) 42)))))

(deftest test-enhance
  (traverse/traverse!
   [{:number 0, ::traverse/handler xform-sync, ::traverse/call-protocol :sync}]
   {:enhancer enhancer
    :enhance? (constantly true)})
  (is true))

(deftest test-failing-enhance
  (is (thrown? clojure.lang.ExceptionInfo
               (traverse/traverse!
                [{:number 0, ::traverse/handler xform-sync, ::traverse/call-protocol :sync}]
                {:enhancer failing-enhancer
                 :enhance? (constantly true)}))))

(deftest test-process-as-seq
  (doseq [p [1 4 16 128]]
    (testing (str "parallelism " p)
      (doseq [[call-type handler protocol] [["sync" xform-sync :sync] ["async" xform-async :callback] ["mixed" xform-sync-random :sync]]]
        (testing (str call-type " calls")
          (let [items (traverse/leaf-seq [{:number 0, ::traverse/handler handler, ::traverse/call-protocol protocol}] {:parallelism p})
                numbers (map :number items)]
            (is (= (sort numbers) (range 100 1000)))))))))

(deftest test-errors
  (is (thrown? clojure.lang.ExceptionInfo
               (traverse/traverse! [{:number 0, ::traverse/handler xform-erroring, ::traverse/call-protocol :sync}] {})))
  #_
  (let [items (traverse/leaf-seq [{:number 0, ::traverse/handler xform-erroring, ::traverse/call-protocol :sync}] {})
        numbers (remove nil? (map :number items))]
    (is (= (count (filter ::traverse/error items)) 1))
    (is (= (set numbers)
           (set/difference (set (range 100 1000))
                           (set (range 500 600)))))))

(deftest test-priority
  (let [items (traverse/leaf-seq [{:number 0, ::traverse/handler xform-sync, ::traverse/call-protocol :sync}]
                                 {:parallelism 1, :prioritize? true})
        numbers (map :number items)]
    (is (= numbers (for [x (range 99 9 -1) y (range 10)] (+ (* 10 x) y))))))

(deftest test-item-chan
  (let [item-chan (async/chan)
        cnt (atom 0)
        channels (traverse/launch [{:number 0, ::traverse/handler xform-sync, ::traverse/call-protocol :sync}]
                                  {:item-chan item-chan})]
    (loop []
      (let [[items _] (async/alts!! [item-chan (:terminate-chan channels)])]
        (when items
          (swap! cnt + (count items))
          (recur))))
    (traverse/close-all! channels)
    (is (= @cnt 999))))

;; This test is the last one for a good reason. It touches
;; the undocumented innards of core.async in order to terminate its
;; thread pool and then reinitialize it again. The
;; InterruptedException messages it displays are normal.

;; 2020-01-21: commenting it out because it failed in CI once
;; (https://circleci.com/gh/nathell/skyscraper/60), and
;; it's not trivial to make skyscraper.core contexts serializable
;; anyway.

#_
(deftest test-interrupt
  (let [seed [{:number 0, ::traverse/handler `xform-async, ::traverse/call-protocol :callback}]
        options {:parallelism 2, :resume-file "/tmp/skyscraper-resume"}
        items (traverse/leaf-seq seed options)]    ; fire up resumable traversal
    (dorun (take 420 items))                       ; consume some elements
    (.shutdownNow @#'async/thread-macro-executor)  ; forcibly terminate threads in pool
    (alter-var-root #'async/thread-macro-executor  ; reinitialize thread pool
                    (fn [_] (java.util.concurrent.Executors/newCachedThreadPool
                             (clojure.core.async.impl.concurrent/counted-thread-factory "async-thread-macro-%d" true))))
    (let [items' (traverse/leaf-seq seed options)] ; restart traversal
      (is (= (count items') 480)))))               ; we should have this many left

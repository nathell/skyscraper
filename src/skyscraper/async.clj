(ns skyscraper.async
  (:require
    [clojure.core.async :as async
     :refer [<! <!! >! >!! alts! alts!!
             chan close! go go-loop put!]]
    [clojure.data.priority-map :refer [priority-map]]
    [clojure.spec.alpha :as spec]
    [skyscraper.data :refer [separate]]
    [taoensso.timbre :refer [debugf infof warnf errorf]]))

(spec/def :skyscraper/priority nat-int?)
(spec/def :skyscraper/terminate boolean?)
(spec/def :skyscraper/call-protocol #{:sync :callback})
(spec/def :skyscraper/context (spec/keys :opt-un [:skyscraper/priority :skyscraper/terminate]))

(defn priority [ctx]
  (:skyscraper/priority ctx 0))

(defn add-to-todo [todo new-items]
  (if (map? todo)
    (into todo (map #(vector % (priority %)) new-items))
    (into todo new-items)))

(defn initial-state [prioritize? items]
  {:todo (add-to-todo (if prioritize?
                        (priority-map)
                        (list))
                      items)
   :doing #{}})

(let [x (Object.)]
  (defn printff [& args]
    (let [s (apply format args)]
      (locking x
        (print s)
        (flush)))))

(defn gimme [{:keys [todo doing] :as s}]
  #_(prn "Gimme:" (desc todo) (desc doing))
  (if-let [popped (first todo)]
    (let [popped (if (map? todo) (key popped) popped)]
      [popped
       {:todo (pop todo)
        :doing (conj doing popped)}])
    [nil s]))

(defn pop-n [n todo]
  (if-not (map? todo)
    [(take n todo) (nth (iterate #(if (empty? %) % (pop %)) todo) n)]
    [(map key (take n todo)) (nth (iterate pop todo) n)]))

(defn done [{:keys [todo doing] :as s}
            {:keys [done new-items]}
            want]
  (if-not (contains? doing done)
    {:unexpected done, :state s}
    (let [all-todo (add-to-todo todo new-items)
          [giveaway new-todo] (pop-n want all-todo)
          doing (-> doing (disj done) (into giveaway))]
      {:want (- want (count giveaway))
       :giveaway giveaway
       :terminate (and (empty? doing) (empty? new-todo))
       :state {:todo new-todo, :doing doing}})))

(defn governor [{:keys [prioritize? parallelism]} seed control-chan data-chan terminate-chan]
  (go-loop [state (initial-state prioritize? seed)
            want 0
            terminating nil]
    (debugf "[governor] Waiting for message")
    (printff "%10d/%-10d\r" (count (:todo state)) (count (:doing state)))
    (let [message (<! control-chan)]
      (debugf "[governor] Got %s" (if (= message :gimme) "gimme" "message"))
      (cond
        terminating (when (pos? terminating)
                      (>! data-chan {:skyscraper/terminate true})
                      (if (= terminating 1)
                        (close! terminate-chan)
                        (recur state want (dec terminating))))
        (= message :gimme) (let [[res state] (gimme state)]
                             (debugf "[governor] Giving")
                             (if res
                               (do
                                 (>! data-chan res)
                                 (recur state want nil))
                               (recur state (inc want) nil)))
        :otherwise (let [{:keys [unexpected want giveaway terminate state]}
                         (done state message want)]
                     (cond
                       unexpected (do
                                    (errorf "[governor] Unexpected message: %s" message)
                                    (recur state want nil))
                       terminate (do
                                   (debugf "[governor] Entering termination mode")
                                   (dotimes [i want]
                                     (>! data-chan {:skyscraper/terminate true}))
                                   (recur state want (- parallelism want)))
                       :else (do
                               (debugf "[governor] Giving away: %d" (count giveaway))
                               (doseq [item giveaway]
                                 (>! data-chan item))
                               (recur state want nil))))))))

(defn processed [context results]
  {:done context, :new-items results})

(defn worker [options i control-chan data-chan leaf-chan]
  (let [options (assoc options :skyscraper/worker i)]
    (go-loop []
      (debugf "[worker %d] Sending gimme" i)
      (>! control-chan :gimme)
      (debugf "[worker %d] Waiting for reply" i)
      (let [{:keys [:skyscraper/terminate :skyscraper/processor :skyscraper/call-protocol] :as context} (<! data-chan)]
        (if terminate
          (debugf "[worker %d] Terminating" i)
          (do
            (case call-protocol
              :sync (let [new-contexts (processor context)
                          [non-leaves leaves] (separate :skyscraper/processor new-contexts)]
                      (debugf "[worker %d] %d leaves, %d inner nodes produced" i (count leaves) (count non-leaves))
                      (when (and leaf-chan (seq leaves))
                        (>! leaf-chan leaves))
                      (>! control-chan (processed context non-leaves)))
              :callback (processor context (fn [new-contexts]
                                             (let [[non-leaves leaves] (separate :skyscraper/processor new-contexts)]
                                               (debugf "[worker %d] %d leaves, %d inner nodes produced" i (count leaves) (count non-leaves))
                                               (when (and leaf-chan (seq leaves))
                                                 (>!! leaf-chan leaves))
                                               (>!! control-chan (processed context non-leaves))))))
            (recur)))))))

(def default-options
  {:prioritize? false
   :parallelism 4})

(defn launch [seed options]
  (let [{:keys [parallelism leaf-chan] :as options} (merge default-options options)
        control-chan (chan)
        data-chan (chan)
        terminate-chan (chan)]
    (governor options seed control-chan data-chan terminate-chan)
    (dotimes [i parallelism]
      (worker options i control-chan data-chan leaf-chan))
    {:control-chan control-chan
     :data-chan data-chan
     :terminate-chan terminate-chan
     :leaf-chan leaf-chan}))

(defn wait! [{:keys [terminate-chan]}]
  (<!! terminate-chan))

(defn close-all! [{:keys [control-chan data-chan leaf-chan]}]
  (close! control-chan)
  (close! data-chan)
  (when leaf-chan
    (close! leaf-chan))
  nil)

(defn process! [seed options]
  (let [channels (launch seed options)]
    (wait! channels)
    (close-all! channels)))

(defn chan->seq [ch channels]
  (lazy-seq
   (let [[items _] (alts!! [ch (:terminate-chan channels)])]
     (if items
       (concat items (chan->seq ch channels))
       (close-all! channels)))))

(defn process-as-seq [seed options]
  (let [leaf-chan (chan)
        options (assoc options :leaf-chan leaf-chan)
        channels (launch seed options)]
    (chan->seq leaf-chan channels)))

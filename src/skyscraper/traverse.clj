(ns skyscraper.traverse
  "Parallelized context tree traversal.

  First, some definitions (sketchy – some details omitted):

  1. A _handler_ is a function taking a map and returning a seq of
     maps (or a symbol naming such a function).
  2. A _context_ is a map that may contain a special key,
     `::handler`, describing a handler that you may run on it.

  Now imagine that we have a root context. We can run its `::handler`
  on it, obtaining a series of child contexts. If these contexts in
  turn contain their own `::handler`s, we can invoke each on its
  associated context, obtaining another series of grandchild contexts.
  Repeatedly applying this process gives rise to a tree, called a
  _context tree_.

  We call that tree _implicit_ because it is never reified as a whole
  in the process; rather, its nodes are computed individually.

  This ns implements context tree traversal parallelized using core.async,
  with the following provisos:

  - A handler can be either synchronous (in which case it's a function
    taking context and returning seq of contexts) or asynchronous (in
    which case it takes a seq of contexts and a callback, should return
    immediately, and should arrange for that callback to be called with
    a list of return contexts when it's ready). Whether a handler is
    synchronous or asynchronous depends on a context's `::call-protocol`.

  - It supports context priorities, letting you control the order in which
    the context tree nodes will be visited. These are specified by the
    `::priority` context key: the less the number, the higher the priority."
  (:require
    [clojure.core.async :as async
     :refer [<! <!! >! >!! alts! alts!!
             chan close! go go-loop put! thread]]
    [clojure.data.priority-map :refer [priority-map]]
    [clojure.java.io :as io]
    [skyscraper.data :refer [separate]]
    [taoensso.timbre :as timbre :refer [debugf infof warnf errorf]]))

(defn- priority [ctx]
  (::priority ctx 0))

(defn- add-to-todo [todo new-items]
  (if (map? todo)
    (into todo (map #(vector % (priority %)) new-items))
    (into todo new-items)))

(defn- initial-state [prioritize? items]
  {:todo (add-to-todo (if prioritize?
                        (priority-map)
                        (list))
                      items)
   :doing #{}})

(defn- gimme [{:keys [todo doing] :as s}]
  (if-let [popped (first todo)]
    (let [popped (if (map? todo) (key popped) popped)]
      [popped
       {:todo (pop todo)
        :doing (conj doing popped)}])
    [nil s]))

(defn- pop-n [n todo]
  (let [pop' #(if (empty? %) % (pop %))]
    (if-not (map? todo)
      [(take n todo) (nth (iterate pop' todo) n)]
      [(map key (take n todo)) (nth (iterate pop' todo) n)])))

(defn- done [{:keys [todo doing] :as s}
             {:keys [done new-items] :as t}
             want]
  (cond
    (not (contains? doing done)) {:unexpected done, :state s}

    (::error (first new-items)) {:want want, :error (first new-items)}

    :otherwise
    (let [all-todo (add-to-todo todo new-items)
          [giveaway new-todo] (pop-n want all-todo)
          doing (-> doing (disj done) (into giveaway))]
      {:want (- want (count giveaway))
       :giveaway giveaway
       :terminate (and (empty? doing) (empty? new-todo))
       :state {:todo new-todo, :doing doing}})))

(defn- atomic-spit [path data]
  (let [temp (java.io.File/createTempFile "spit" nil)]
    (spit temp data)
    (.renameTo temp (io/file path))))

(defn- read-resume-file [filename]
  (when (and filename (.exists (io/file filename)))
    (let [{:keys [todo doing]} (read-string (slurp filename))]
      {:todo (-> (list)
                 (into doing)
                 (into todo))
       :doing #{}})))

(defn- governor [{:keys [prioritize? parallelism resume-file]} seed {:keys [control-chan data-chan terminate-chan]}]
  (go-loop [state (or (read-resume-file resume-file)
                      (initial-state prioritize? seed))
            want 0
            terminating nil]
    (when resume-file
      (atomic-spit resume-file state))
    (debugf "[governor] Waiting for message")
    (let [message (<! control-chan)]
      (debugf "[governor] Got %s" (if (= message :gimme) "gimme" "message"))
      (cond
        terminating (when (pos? terminating)
                      (>! data-chan {::terminate true})
                      (if (= terminating 1)
                        (do
                          (when resume-file
                            (.delete (io/file resume-file)))
                          (close! terminate-chan))
                        (recur state want (dec terminating))))
        (= message :gimme) (let [[res state] (gimme state)]
                             (debugf "[governor] Giving")
                             (if res
                               (do
                                 (>! data-chan res)
                                 (recur state want nil))
                               (recur state (inc want) nil)))
        :otherwise (let [{:keys [unexpected want giveaway terminate state error]}
                         (done state message want)]
                     (cond
                       unexpected (do
                                    (errorf "[governor] Unexpected message: %s" message)
                                    (recur state want nil))
                       terminate (do
                                   (debugf "[governor] Entering termination mode")
                                   (dotimes [i want]
                                     (>! data-chan {::terminate true}))
                                   (recur state want (- parallelism want)))
                       error (do
                               (debugf "[governor] Error encountered, entering abnormal termination")
                               (loop [cnt (- parallelism want)]
                                 (when (pos? cnt)
                                   (let [msg (<! control-chan)]
                                     (recur (if (= msg :gimme)
                                              (dec cnt)
                                              cnt)))))
                               (dotimes [i parallelism]
                                 (>! data-chan {::terminate true}))
                               (>! terminate-chan error)
                               (close! terminate-chan))
                       :else (do
                               (debugf "[governor] Giving away: %d" (count giveaway))
                               (doseq [item giveaway]
                                 (>! data-chan item))
                               (recur state want nil))))))))

(defn- processed [context results]
  {:done context, :new-items results})

(defn- propagate-new-contexts [{:keys [item-chan leaf-chan control-chan]} enhance i context new-contexts]
  (let [enhanced (mapv enhance new-contexts)
        [non-leaves leaves] (separate ::handler enhanced)
        err (first (filter ::error enhanced))]
    (debugf "[worker %d] %d leaves, %d inner nodes produced" i (count leaves) (count non-leaves))
    (when (and item-chan (seq new-contexts))
      (>!! item-chan new-contexts))
    (when (and leaf-chan (seq leaves))
      (>!! leaf-chan leaves))
    (when err
      (timbre/error err (format "[worker %d] Handler threw an error" i)))
    (>!! control-chan (processed context (if err
                                           [err]
                                           non-leaves)))))

(defn- wrapped-error [context error]
  {::context context, ::error error})

(defmacro capture-errors [context & body]
  `(let [context# ~context]
     (try
       ~@body
       (catch Exception e#
         [(wrapped-error context# e#)]))))

(defn enhancer-loop [{:keys [enhancer-input-chan enhancer-output-chan]} f]
  (loop []
    (when-let [item (async/<!! enhancer-input-chan)]
      (let [new-item (try
                       (f item)
                       (catch Exception e
                         (wrapped-error item e)))]
        (async/>!! enhancer-output-chan new-item)
        (recur))))) ; XXX: do we want to recur even if an error had occurred?

(defn- worker [{:keys [enhance?] :as options} i {:keys [control-chan data-chan enhancer-input-chan enhancer-output-chan] :as channels}]
  (let [options (assoc options ::worker i)
        enhance (fn [x]
                  (if (and enhancer-input-chan enhance? (enhance? x))
                    (do
                      (>!! enhancer-input-chan x)
                      (<!! enhancer-output-chan))
                    x))]
    (thread
      (loop []
        (debugf "[worker %d] Sending gimme" i)
        (>!! control-chan :gimme)
        (debugf "[worker %d] Waiting for reply" i)
        (let [{:keys [::terminate ::handler ::call-protocol] :as context} (<!! data-chan)
              handler (cond (nil? handler) nil
                            (fn? handler)  handler
                            :otherwise     (ns-resolve *ns* handler))]
          (if terminate
            (debugf "[worker %d] Terminating" i)
            (do
              (case call-protocol
                :sync (propagate-new-contexts channels enhance i context (capture-errors context (handler context options)))
                :callback (handler context options (partial propagate-new-contexts channels enhance i context)))
              (recur))))))))

(def default-options
  {:prioritize? false
   :parallelism 4})

(defn launch
  "Launches a parallel tree traversal. Spins up a number of core.async
  threads that actually perform it, then immediately returns a map of
  channels used to orchestrate the process – most importantly,
  `:terminate-chan` will be closed when the process completes.

  `options` is a map that may include:

    :leaf-chan     a channel where seqs of tree leaves will be put
                   (default nil)
    :item-chan     a channel where seqs of tree nodes will be put
                   (default nil)
    :parallelism   number of worker threads to create (default 4)
    :prioritize?   take into account ::priority values (default false)
    :on-end        a zero-argument callback, to be called when scraping
                   ends (either normally or abnormally)

  To wait until traversal is complete, use `wait!`. Otherwise, remember
  to use `close-all!` to close the channels returned by this
  function. See `traverse!` or `chan->seq` for an example of how to
  put it together."
  [seed options]
  (let [{:keys [parallelism leaf-chan item-chan enhancer on-end] :as options} (merge default-options options)
        channels (merge {:control-chan (chan)
                         :data-chan (chan)
                         :terminate-chan (chan)
                         :leaf-chan leaf-chan
                         :item-chan item-chan
                         :on-end on-end}
                        (when enhancer
                          {:enhancer-input-chan (chan)
                           :enhancer-output-chan (chan)}))]
    (governor options seed channels)
    (dotimes [i parallelism]
      (worker options i channels))
    (cond-> channels
      enhancer (assoc :enhancer-terminate-chan
                      (thread
                        (enhancer options channels)
                        nil)))))

(defn- throw-handler-error!
  "Throws an ExceptionInfo about a handler throwing an error."
  [error]
  (throw (ex-info "Handler threw an error"
                  (::context error)
                  (::error error))))

(defn close-all!
  "Closes channels used by the traversal process."
  [channels]
  (doseq [[k ch] channels :when (and ch (not (#{:enhancer-terminate-chan :on-end} k)))]
    (close! ch))
  (when-let [ch (:enhancer-terminate-chan channels)]
    (<!! ch))
  (when-let [on-end (:on-end channels)]
    (on-end))
  nil)

(defn wait!
  "Waits until the scraping process is complete."
  [{:keys [terminate-chan] :as channels}]
  (let [error (<!! terminate-chan)]
    (close-all! channels)
    (when error
      (throw-handler-error! error))))

(defn traverse!
  "Traverses a tree and returns after the process is complete.
  Parameters are the same as in `launch`."
  [seed options]
  (let [channels (launch seed options)]
    (wait! channels)))

(defn- chan->seq [ch {:keys [terminate-chan] :as channels}]
  (lazy-seq
   (let [[items out-ch] (alts!! [ch terminate-chan])]
     (cond
       (and items (= out-ch terminate-chan))
       #_=> (do
              (close-all! channels)
              (throw-handler-error! items))
       items
       #_=> (concat items (chan->seq ch channels))
       :otherwise
       #_=> (close-all! channels)))))

(defn leaf-seq
  "Returns a lazy seq of leaf nodes from a tree traversal. Any channels
  created will be automatically closed when the seq is fully consumed."
  [seed options]
  (let [leaf-chan (chan)
        options (assoc options :leaf-chan leaf-chan)
        channels (launch seed options)]
    (chan->seq leaf-chan channels)))

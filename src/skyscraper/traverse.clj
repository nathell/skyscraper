(ns skyscraper.traverse
  "Parallelized context tree traversal.

  First, some definitions (sketchy – some details omitted):

  1. A _handler_ is a function taking a map and returning a seq of
     maps.
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
    [skyscraper.data :refer [separate]]
    [taoensso.timbre :refer [debugf infof warnf errorf]]))

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

(defn- governor [{:keys [prioritize? parallelism]} seed {:keys [control-chan data-chan terminate-chan]}]
  (go-loop [state (initial-state prioritize? seed)
            want 0
            terminating nil]
    (debugf "[governor] Waiting for message")
    (let [message (<! control-chan)]
      (debugf "[governor] Got %s" (if (= message :gimme) "gimme" "message"))
      (cond
        terminating (when (pos? terminating)
                      (>! data-chan {::terminate true})
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
                                     (>! data-chan {::terminate true}))
                                   (recur state want (- parallelism want)))
                       :else (do
                               (debugf "[governor] Giving away: %d" (count giveaway))
                               (doseq [item giveaway]
                                 (>! data-chan item))
                               (recur state want nil))))))))

(defn- processed [context results]
  {:done context, :new-items results})

(defn- propagate-new-contexts [{:keys [item-chan leaf-chan control-chan]} i context new-contexts]
  (let [[non-leaves leaves] (separate ::processor new-contexts)]
    (debugf "[worker %d] %d leaves, %d inner nodes produced" i (count leaves) (count non-leaves))
    (when (and item-chan (seq new-contexts))
      (>!! item-chan new-contexts))
    (when (and leaf-chan (seq leaves))
      (>!! leaf-chan leaves))
    (>!! control-chan (processed context non-leaves))))

(defmacro capture-errors [& body]
  `(try
     ~@body
     (catch Exception e#
       [{::error e#}])))

(defn- worker [options i {:keys [control-chan data-chan] :as channels}]
  (let [options (assoc options ::worker i)]
    (thread
      (loop []
        (debugf "[worker %d] Sending gimme" i)
        (>!! control-chan :gimme)
        (debugf "[worker %d] Waiting for reply" i)
        (let [{:keys [::terminate ::processor ::call-protocol] :as context} (<!! data-chan)]
          (if terminate
            (debugf "[worker %d] Terminating" i)
            (do
              (case call-protocol
                :sync (propagate-new-contexts channels i context (capture-errors (processor context)))
                :callback (processor context (partial propagate-new-contexts channels i context)))
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

  To wait until traversal is complete, use `wait!`. Also, remember to
  use `close-all!` to close the channels returned by this
  function. See `traverse!` or `chan->seq` for an example of how to
  put it together."
  [seed options]
  (let [{:keys [parallelism leaf-chan item-chan] :as options} (merge default-options options)
        channels {:control-chan (chan)
                  :data-chan (chan)
                  :terminate-chan (chan)
                  :leaf-chan leaf-chan
                  :item-chan item-chan}]
    (governor options seed channels)
    (dotimes [i parallelism]
      (worker options i channels))
    channels))

(defn wait!
  "Waits until the scraping process is complete."
  [{:keys [terminate-chan]}]
  (<!! terminate-chan))

(defn close-all!
  "Closes channels used by the traversal process. Call this function
  after `wait!` returns."
  [{:keys [control-chan data-chan leaf-chan item-chan]}]
  (close! control-chan)
  (close! data-chan)
  (when leaf-chan
    (close! leaf-chan))
  (when item-chan
    (close! item-chan))
  nil)

(defn traverse!
  "Traverses a tree and returns after the process is complete.
  Parameters are the same as in `launch`."
  [seed options]
  (let [channels (launch seed options)]
    (wait! channels)
    (close-all! channels)))

(defn- chan->seq [ch channels]
  (lazy-seq
   (let [[items _] (alts!! [ch (:terminate-chan channels)])]
     (if items
       (concat items (chan->seq ch channels))
       (close-all! channels)))))

(defn leaf-seq
  "Returns a lazy seq of leaf nodes from a tree traversal. Any channels
  created will be automatically closed when the seq is fully consumed."
  [seed options]
  (let [leaf-chan (chan)
        options (assoc options :leaf-chan leaf-chan)
        channels (launch seed options)]
    (chan->seq leaf-chan channels)))

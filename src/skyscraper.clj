(ns skyscraper
  (:require
    [clojure.core.async :as async
     :refer [<! <!! >! >!! alts! alts!!
             chan close! go go-loop put!]]
    [clojure.set :refer [intersection]]
    [clojure.string :as string]
    [org.httpkit.client :as http]
    [reaver])
  (:import [java.net URL]
           [org.httpkit.client HttpClient TimeoutException]))

(defonce processors (atom {}))

(defn defprocessor [name & {:keys [process-fn], :as args}]
  (swap! processors assoc name (merge {:name name} args)))

(defn ensure-seq [x]
  (if (map? x) [x] (doall x)))

(defn run-processor
  ([processor-name document] (run-processor processor-name document {}))
  ([processor-name document context]
   (let [processor (@processors processor-name)]
     (ensure-seq ((:process-fn processor) document context)))))

(defn dissoc-internal [ctx]
  (let [removed-keys #{:method :processor :desc :form-params}]
    (into {}
          (remove (fn [[k _]] (or (contains? removed-keys k)
                                  (= (namespace k) (namespace ::x)))))
          ctx)))

(defn allows?
  "True if all keys in m1 that are also in m2 have equal values in both maps."
  [m1 m2]
  (let [ks (intersection (set (keys m1)) (set (keys m2)))]
    (if (seq ks)
      (let [f (apply juxt ks)]
        (= (f m1) (f m2)))
      true)))

(defn filter-contexts
  [data params]
  (if-let [only (:only params)]
    (let [filter-fn (if (fn? only)
                      only
                      (fn [x] (some #(allows? % x) (ensure-seq only))))]
      (filter filter-fn data))
    data))

(defn merge-urls
  "Fills the missing parts of new-url (which can be either absolute,
   root-relative, or relative) with corresponding parts from url
   (an absolute URL) to produce a new absolute URL."
  [url new-url]
  (if (string/starts-with? new-url "?")
    (str (string/replace url #"\?.*" "") new-url)
    (str (URL. (URL. url) new-url))))

(defn merge-contexts [old new]
  (let [preserve (dissoc-internal old)
        new-url (if-let [u (:url new)]
                  (merge-urls (:url old) u))
        new (if new-url
              (assoc new :url new-url)
              new)]
    (merge preserve new)))

(defn gimme [{:keys [todo doing] :as s}]
  (if-let [popped (first todo)]
    [popped
     {:todo (rest todo)
      :doing (conj doing popped)}]
    [nil s]))

(let [x (Object.)]
  (defn log [who i & items]
    (locking x
      (if i
        (print (str "[" who " " i "] "))
        (print (str "[" who "] ")))
      (apply println items)
      (flush))))

(defn done [{:keys [todo doing] :as s}
            {:keys [done new-items]}
            want]
  (if-not (contains? doing done)
    {:unexpected done, :state s}
    (let [all-todo (into todo new-items)
          giveaway (take want all-todo)
          new-todo (drop want all-todo)
          doing (-> doing (disj done) (into giveaway))]
      {:want (- want (count giveaway))
       :giveaway giveaway
       :terminate (and (empty? doing) (empty? new-todo))
       :state {:todo new-todo, :doing doing}})))

(defn add-todo [state item]
  (update state :todo conj item))

(defn processed [context result]
  {:done context, :new-items [result]})

(defn initial-state [items]
  {:todo (into (list) items)
   :doing #{}})

(defn governor [{:keys [parallelism]} seed control-chan data-chan terminate-chan]
  (go-loop [state (initial-state seed)
            want 0
            terminating nil]
    (let [dlog (partial log "governor" nil)
          _ (dlog "Waiting for message" (count (:todo state)) (count (:doing state)) want)
          message (<! control-chan)]
      (dlog (if (= message :gimme) "Got gimme" "Got message"))
      (cond
        terminating (when (pos? terminating)
                      (>! data-chan :terminate)
                      (if (= terminating 1)
                        (close! terminate-chan)
                        (recur state want (dec terminating))))
        (= message :gimme) (let [[res state] (gimme state)]
                             (dlog "Giving" (keys res))
                             (if res
                               (do
                                 (>! data-chan res)
                                 (recur state want nil))
                               (recur state (inc want) nil)))
        :otherwise (let [{:keys [unexpected want giveaway terminate state]}
                         (done state message want)]
                     (cond
                       unexpected (do
                                    (dlog "Unexpected:" message)
                                    (recur state want nil))
                       terminate (do
                                   (dlog "Entering termination mode")
                                   (dotimes [i want]
                                     (>! data-chan :terminate))
                                   (recur state want (- parallelism want)))
                       :else (do
                               (dlog "Giving away:" (mapv keys giveaway))
                               (doseq [item giveaway]
                                 (>! data-chan item))
                               (recur state want nil))))))))

(defn describe [ctx]
  (:url ctx))

(defn download [{:keys [timeout retries]}
                client sem context control-chan]
  (let [orig context
        dlog (partial log "download" nil)
        req (merge {:client client
                    :timeout timeout
                    :method :get}
                   (select-keys context [:url :method :form-params]))
        send-retry (fn [error]
                     (let [retry (inc (or (::retry context) 0))]
                       (if (< retry retries)
                         (do
                           (dlog "Unexpected error - retry" retry error "context:" context)
                           (>!! control-chan (processed orig (assoc context ::retry retry))))
                         (do
                           (dlog "Unexpected error - giving up" error "context:" context)
                           (>!! control-chan (processed orig {::error error, ::context context}))))))
        handler (fn [{:keys [opts body headers status error] :as resp}]
                        (cond
                          error (if (instance? TimeoutException error)
                                  (do
                                    (dlog "Timeout:" context)
                                    (>!! control-chan (processed orig context)))
                                  (send-retry error))
                          (= status 200) (do
                                           (dlog "downloaded" (describe context))
                                           #_(when ckey
                                             (cache/save-string html-cache ckey (:body resp)))
                                           (>!! control-chan (processed orig (assoc context ::response resp))))
                          :otherwise (send-retry {:status status}))
                        (.release sem))]
          (dlog "waiting")
          (.acquire sem)
          (dlog "downloading" (describe context))
          (http/request req handler)))

(defn process [{:keys [number] :as job}]
  (let [new-items (when (< number 100)
                    (for [i (range 1 4)]
                      {:number (+ (* number 10) i)}))]
    {:done job, :new-items new-items}))

(defn worker [options i client sem control-chan data-chan]
  (let [dlog (partial log "worker" i)]
    (go-loop []
      (dlog "Sending gimme")
      (>! control-chan :gimme)
      (dlog "Waiting for reply")
      (let [context (<! data-chan)]
        (cond
          (= context :terminate) (dlog "Terminating")
          (::error context) (dlog "Got error:" (::error context))
          (not (::response context)) (download options client sem context control-chan)
          :otherwise (do
                       (let [document (reaver/parse (get-in context [::response :body]))
                             processor (@processors (:processor context))
                             output (as-> (try
                                            (-> document
                                                ((:process-fn processor) context)
                                                ensure-seq)
                                            (catch Exception e
                                              (dlog "processor threw error: %s" (:processor context) (describe context))
                                              [])) result
                                      (map #(merge-contexts context %) result)
                                      #_(maybe-store-in-db (:db params) processor result)
                                      (filter-contexts result options))
                             with-processor (filter :processor output)
                             without-processor (remove :processor output)]
                         (dlog "Produced" (count without-processor))
                         (>! control-chan {:done context, :new-items with-processor}))))
        (when-not (= context :terminate)
          (recur))))))

(def default-options
  {:max-connections 10,
   :parallelism 4,
   :timeout 60000,
   :retries 5})

(defn scrape [seed & {:as options}]
  (let [options (merge default-options options)
        {:keys [max-connections parallelism]} options
        client (HttpClient. max-connections)
        sem (java.util.concurrent.Semaphore. max-connections)
        control-chan (chan)
        data-chan (chan)
        terminate-chan (chan)]
    (governor options seed control-chan data-chan terminate-chan)
    (dotimes [i parallelism]
      (worker options i client sem control-chan data-chan))
    (<!! terminate-chan)
    (close! control-chan)
    (close! data-chan)
    (log "run" nil "Terminated.")))

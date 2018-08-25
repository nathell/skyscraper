(ns skyscraper
  (:require
    [clj-http.client :as http]
    [clj-http.conn-mgr :as http-conn]
    [clj-http.core :as http-core]
    [clojure.core.async :as async
     :refer [<! <!! >! >!! alts! alts!!
             chan close! go go-loop put!]]
    [clojure.set :refer [intersection]]
    [clojure.string :as string]
    [reaver]
    [skyscraper.cache :as cache]
    [skyscraper.sqlite :as sqlite]
    [taoensso.timbre :refer [debugf warnf errorf]])
  (:import [java.net URL]
           [org.httpkit.client HttpClient TimeoutException]))

;;; Micro-templating framework

(defn format-template
  "Fills in a template string with moving parts from m. template should be
   a string containing 'variable names' starting with colons; these names
   are extracted, converted to keywords and looked up in m, which should be
   a map (or a function taking keywords and returning strings).

   Example:
   (format-template \":group/:user/index\" {:user \"joe\", :group \"admins\"})
   ;=> \"admins/joe/index\" "
  [template m]
  (let [re #":[a-z-]+"
        keys (map #(keyword (subs % 1)) (re-seq re template))
        fmt (string/replace template re "%s")]
    (apply format fmt (map m keys))))

(defn cache-key [{:keys [cache-template cache-key-fn]} context]
  (let [cache-key-fn (or cache-key-fn
                         (when cache-template
                           (partial format-template cache-template)))]
    (when cache-key-fn
      (cache-key-fn context))))

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

(defn separate [f s]
  [(filter f s) (filter (complement f) s)])

(defn maybe-store-in-db [db {:keys [name db-columns id] :as q} contexts]
  (if (and db db-columns)
    (let [[skipped inserted] (separate ::db-skip contexts)
          new-items (sqlite/insert-all! db name id db-columns inserted)]
      (into (vec skipped) new-items))
    contexts))

(defn gimme [{:keys [todo doing] :as s}]
  (if-let [popped (first todo)]
    [popped
     {:todo (rest todo)
      :doing (conj doing popped)}]
    [nil s]))

(let [x (Object.)]
  (defn printff [& args]
    (let [s (apply format args)]
      (locking x
        (print s)
        (flush)))))

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
    (debugf "[governor] Waiting for message")
    (printff "%10d/%-10d\r" (count (:todo state)) (count (:doing state)))
    (let [message (<! control-chan)]
      (debugf "[governor] Got %s" (if (= message :gimme) "gimme" "message"))
      (cond
        terminating (when (pos? terminating)
                      (>! data-chan :terminate)
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
                                    (errorf "[governor] Unexpected message:" message)
                                    (recur state want nil))
                       terminate (do
                                   (debugf "[governor] Entering termination mode")
                                   (dotimes [i want]
                                     (>! data-chan :terminate))
                                   (recur state want (- parallelism want)))
                       :else (do
                               (debugf "[governor] Giving away: %d" (count giveaway))
                               (doseq [item giveaway]
                                 (>! data-chan item))
                               (recur state want nil))))))))

(defn describe [ctx]
  (cond->
      (:url ctx)
    (:http/cookies ctx) (str " cookies: " (pr-str (:http/cookies ctx)))
    (:form-params ctx) (str " form-params: " (pr-str (:form-params ctx)))))

(defn download [options sem cm ckey context control-chan]
  (let [orig context
        req (merge {:method :get}
                   (select-keys context [:method :form-params :url]))
        success-fn (fn [resp]
                     (debugf "[download] Downloaded %s" (describe context))
                     (let [result (cond-> context
                                    true (assoc ::response resp)
                                    (:cookies resp) (update :http/cookies merge (:cookies resp)))]
                       (when ckey
                         (cache/save-string (:html-cache options) ckey (:body resp)))
                       (>!! control-chan (processed orig result))
                       (.release sem)))
        error-fn (fn [error]
                   (let [retry (inc (or (::retry context) 0))]
                     (if (< retry (:retries options))
                       (do
                         (warnf "[download] Unexpected error %s, retry %s, context %s" error retry context)
                         (>!! control-chan (processed orig (assoc context ::retry retry))))
                       (do
                         (warnf "[download] Unexpected error %s, giving up, context %s" error context)
                         (>!! control-chan (processed orig {::error error, ::context context})))))
                   (.release sem))]
    (debugf "[download] Waiting")
    (.acquire sem)
    (debugf "[download] Downloading %s" (describe context))
    (let [req (merge {:async? true,
                      :connection-manager cm}
                     (when-let [cookies (:http/cookies context)]
                       {:cookies cookies})
                     req (:http-options options))]
      (http/request req
       success-fn
       error-fn))))

(defn worker [options i sem cm control-chan data-chan]
  (go-loop []
    (debugf "[worker %d] Sending gimme" i)
    (>! control-chan :gimme)
    (debugf "[worker %d] Waiting for reply" i)
    (let [context (<! data-chan)
          processor (@processors (:processor context))]
      (cond
        (= context :terminate) (debugf "[worker %d] Terminating" i)
        (::error context) (debugf "[worker %d] Got error: %s" i (::error context))
        (not (::response context)) (let [ckey (cache-key processor context)
                                         cached (when (and ckey (not (:updatable processor)))
                                                  (cache/load-string (:html-cache options) ckey))]
                                     (when cached
                                       (debugf "[worker %d] Retrieved from cache: %s" i (describe context)))
                                     (if cached
                                       (>! control-chan (processed context (assoc context ::response {:body cached})))
                                       (download options sem cm ckey context control-chan)))
        :otherwise (do
                     (let [document (reaver/parse (get-in context [::response :body]))
                           output (as-> (try
                                          (-> document
                                              ((:process-fn processor) context)
                                              ensure-seq)
                                          (catch Exception e
                                            (warnf e "[worker %d] Processor threw error for %s" i (describe context))
                                            [])) result
                                    (map #(merge-contexts context %) result)
                                    (maybe-store-in-db (:db options) processor result)
                                    (filter-contexts result options))
                           with-processor (filter :processor output)
                           without-processor (remove :processor output)]
                       (debugf "[worker %d] Produced" i (count without-processor))
                       (>! control-chan {:done context, :new-items with-processor}))))
      (when-not (= context :terminate)
        (recur)))))

(def default-options
  {:max-connections 10,
   :parallelism 4,
   :timeout 60000,
   :retries 5,
   :conn-mgr-options {},
   :http-options {:redirect-strategy :lax, :as :auto}})

(defn scrape [seed & {:as options}]
  (let [options (merge default-options options)
        cm (http-conn/make-reuseable-async-conn-manager (:conn-mgr-options options))
        {:keys [max-connections parallelism]} options
        sem (java.util.concurrent.Semaphore. max-connections)
        control-chan (chan)
        data-chan (chan)
        terminate-chan (chan)]
    (governor options seed control-chan data-chan terminate-chan)
    (dotimes [i parallelism]
      (worker options i sem cm control-chan data-chan))
    (<!! terminate-chan)
    (close! control-chan)
    (close! data-chan)
    (debugf "Scrape complete.")))

(ns skyscraper
  (:require [clojure.core.async :as async
             :refer [<! <!! >! >!! chan close! go go-loop put!]]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.set :refer [intersection]]
            [clojure.string :as string]
            [org.httpkit.client :as http]
            [reaver]
            [skyscraper.async :refer [lifo-buffer]]
            [skyscraper.cache :as cache]
            [skyscraper.sqlite :as sqlite]
            [taoensso.timbre :as timbre :refer [infof warnf errorf]]
            [taoensso.timbre.appenders.core :as appenders])
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

(defonce processors (atom {}))

(defn extract-cookie [headers]
  (when-let [set-cookie (:set-cookie headers)]
    (first (string/split set-cookie #"; "))))

(defn defprocessor [name & {:keys [process-fn], :as args}]
  (swap! processors assoc name (merge {:name name} args)))

(defn ensure-seq [x]
  (if (map? x) [x] (doall x)))

(defn run-processor
  ([processor-name document] (run-processor processor-name document {}))
  ([processor-name document context]
   (let [processor (@processors processor-name)]
     (ensure-seq ((:process-fn processor) document context)))))

(defn- cookie-headers [context]
  (when-let [cookie (::cookie context)]
    {:headers {"Cookie" cookie}}))

(defn merge-urls
  "Fills the missing parts of new-url (which can be either absolute,
   root-relative, or relative) with corresponding parts from url
   (an absolute URL) to produce a new absolute URL."
  [url new-url]
  (if (string/starts-with? new-url "?")
    (str (string/replace url #"\?.*" "") new-url)
    (str (URL. (URL. url) new-url))))

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

(defn dissoc-internal [ctx]
  (let [removed-keys #{:method :processor :desc :form-params}]
    (into {}
          (remove (fn [[k _]] (or (contains? removed-keys k)
                                  (= (namespace k) (namespace ::x)))))
          ctx)))

(defn describe [ctx]
  (pr-str (dissoc-internal ctx)))

(defn cache-key [{:keys [cache-template cache-key-fn]} context]
  (let [cache-key-fn (or cache-key-fn
                         (when cache-template
                           (partial format-template cache-template)))]
    (when cache-key-fn
      (cache-key-fn context))))

(defn downloader [to-process url-chan body-chan
                  {:keys [max-connections retries timeout html-cache]
                   :or {max-connections 10, retries 5, timeout 60000}}]
  (let [client (HttpClient. max-connections)
        sem (java.util.concurrent.Semaphore. max-connections)]
    (go-loop []
      (infof "downloader: awaiting context")
      (let [context (<! url-chan)
            orig context
            processor (@processors (:processor context))]
        (when context
          (let [ckey (cache-key processor context)
                cached (when (and ckey (not (:updatable processor))) (cache/load-string html-cache ckey))
                context (assoc context ::cache-key ckey)]
            (if cached
              (do
                (infof "downloader: retrieved from cache: %s" (describe context))
                (put! body-chan (assoc context ::response {:body cached} ::orig orig)))
              (let [req (merge {:client client
                                :timeout timeout
                                :method :get}
                               (cookie-headers context)
                               (select-keys context [:url :method :form-params :headers]))
                    handler (fn [{:keys [opts body headers status error] :as resp}]
                              (cond
                                error (if (instance? TimeoutException error)
                                        (do
                                          (warnf "downloader: %s timed out, requeuing" (describe context))
                                          (put! url-chan context))
                                        (let [retry (inc (or (::retry context) 0))]
                                          (if (< retry retries)
                                            (do
                                              (warnf "downloader: %s: unexpected error: %s, retrying" (describe context) error)
                                              (put! url-chan (assoc context ::retry retry)))
                                            (do
                                              (warnf "downloader: %s: unexpected error: %s, giving up" (describe context) error)
                                              (put! body-chan {::error true, ::orig (dissoc orig ::retry ::cache-key)})))))
                                (= status 200) (let [cookie (extract-cookie headers)
                                                     context (cond-> context
                                                               cookie (assoc ::cookie cookie))]
                                                 (infof "downloader: downloaded %s" (describe context))
                                                 (when ckey
                                                   (cache/save-string html-cache ckey (:body resp)))
                                                 (put! body-chan
                                                       (assoc context
                                                              ::response resp
                                                              ::orig orig)))
                                :otherwise (let [retry (inc (or (::retry context) 0))]
                                             (if (< retry retries)
                                               (do
                                                 (warnf "downloader: %s: unexpected status: %s, retrying" (describe context) status)
                                                 (put! url-chan (assoc context ::retry retry)))
                                               (do
                                                 (warnf "downloader: %s: unexpected status: %s, giving up" (describe context) status)
                                                 (put! body-chan {::error true, ::orig (dissoc orig ::retry ::cache-key)})))))
                              (.release sem))]
                (infof "downloader: waiting" (describe context))
                (.acquire sem)
                (infof "downloader: downloading %s" (describe context))
                (http/request req handler))))
          (recur))))))

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

(defn grinder [to-process url-chan body-chan output-chan params]
  (go-loop []
    (infof "grinder: awaiting context, first to process: %s" (pr-str (first @to-process)))
    (when-let [context (<! body-chan)]
      (infof "grinder: grinding %s" (describe context))
      (let [new-items (when-not (::error context)
                        (let [document (reaver/parse (get-in context [::response :body]))
                              processor (@processors (:processor context))]
                          (as-> (try
                                  (-> document
                                      ((:process-fn processor) context)
                                      ensure-seq)
                                  (catch Exception e
                                    (errorf e "grinder: processor %s for %s threw error" (:processor context) (describe context))
                                    [])) result
                            (map #(merge-contexts context %) result)
                            (maybe-store-in-db (:db params) processor result)
                            (filter-contexts result params))))
            new-atom (swap! to-process
                            #(-> %
                                 (disj (::orig context))
                                 (into (filter :processor new-items))))]
        (infof "grinder: Outputting %s (%s/%s) items for %s" (count new-items) (count (filter :processor new-items)) (count (remove :processor new-items)) (describe context))
        (doseq [item new-items]
          (if (:processor item)
            (>! url-chan item)
            (>! output-chan (dissoc-internal item))))
        (if (seq new-atom)
          (recur)
          (do
            (close! url-chan)
            (close! body-chan)
            (close! output-chan)))))))

(defn initialize [seed input-chan]
  (doseq [item seed]
    (>!! input-chan item)))

(defn scrape [seed & {:as params}]
  (let [input-chan (chan)
        body-chan (chan (lifo-buffer 10000))
        output-chan (chan)
        seed (filter-contexts seed params)
        to-process (atom (set seed))]
    (downloader to-process input-chan body-chan (select-keys params [:html-cache]))
    (grinder to-process input-chan body-chan output-chan params)
    (initialize seed input-chan)
    (with-open [f (io/writer "output.dat")]
      (loop [x (<!! output-chan)]
        (when x
          #_(binding [*out* f]
            (prn x))
          (recur (<!! output-chan)))))
    nil))

(defn read-all
  [input]
  (with-open [f (java.io.PushbackReader. (io/reader input))]
    (let [eof (Object.)]
      (doall (take-while #(not= % eof) (repeatedly #(read f false eof)))))))

(defn save-dataset-to-csv
  [data output & [keyseq]]
  (let [keyseq (or keyseq (keys (first data)))]
    (with-open [f (io/writer output)]
      (csv/write-csv f [(map name keyseq)])
      (csv/write-csv f (map (fn [row-data]
                              (map (comp str row-data) keyseq))
                            data)))))

(timbre/merge-config!
 {:appenders {:println {:enabled? false},
              :spit (appenders/spit-appender {:fname "skyscraper-next.log"})}})

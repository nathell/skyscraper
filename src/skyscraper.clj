(ns skyscraper
  (:require
    [clj-http.client :as http]
    [clj-http.conn-mgr :as http-conn]
    [clj-http.core :as http-core]
    [clojure.set :refer [intersection]]
    [clojure.string :as string]
    [net.cgrand.enlive-html :as enlive]
    [reaver]
    [skyscraper.cache :as cache]
    [skyscraper.data :refer [separate]]
    [skyscraper.sqlite :as sqlite]
    [skyscraper.traverse :as traverse]
    [taoensso.timbre :refer [debugf infof warnf errorf]])
  (:import [java.net URL]))

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

;;; Defining processors

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

(defn maybe-store-in-db [db {:keys [name db-columns id] :as q} contexts]
  (if (and db db-columns)
    (let [[skipped inserted] (separate ::db-skip contexts)
          new-items (sqlite/insert-all! db name id db-columns inserted)]
      (into (vec skipped) new-items))
    contexts))

(defn describe [ctx]
  (-> ctx
      dissoc-internal
      (dissoc :url)
      pr-str) #_
  (cond->
      (:url ctx)
    (:http/cookies ctx) (str " cookies: " (pr-str (:http/cookies ctx)))
    (:form-params ctx) (str " form-params: " (pr-str (:form-params ctx)))))

(defn string-resource
  "Returns an Enlive resource for a HTML snippet passed as a string."
  [s]
  (enlive/html-resource (java.io.StringReader. s)))

;;; Scraping

(defn extract-namespaced-keys
  [ns m]
  (into {}
        (comp (filter #(= (namespace (key %)) ns))
              (map (fn [[k v]] [(keyword (name k)) v])))
        m))

(defn init-handler [context options]
  [(assoc context
          ::traverse/handler `download-handler
          ::traverse/call-protocol :callback)])

(defn process-handler [context options]
  (let [document (-> context ::response :body string-resource)
        processor-name (:processor context)
        result (run-processor processor-name document context)]
    [(-> context
         (assoc ::result result)
         (dissoc ::traverse/handler ::traverse/call-protocol))]))

(defn download-handler [context {:keys [connection-manager download-semaphore retries] :as options} callback]
  (let [req (merge {:method :get, :url (:url context)}
                   (extract-namespaced-keys "http" context))
        success-fn (fn [resp]
                     (debugf "[download] Downloaded %s" (describe context))
                     (.release download-semaphore)
                     (callback
                      [(cond-> context
                          true (assoc ::response resp
                                      ::traverse/handler `process-handler
                                      ::traverse/call-protocol :sync)
                          (:cookies resp) (update :http/cookies merge (:cookies resp)))]))
        error-fn (fn [error]
                   (.release download-semaphore)
                   (let [retry (inc (or (::retry context) 0))]
                     (callback
                      [(if (< retry (:retries options))
                          (do
                            (warnf "[download] Unexpected error %s, retry %s, context %s" error retry context)
                            (assoc context ::retry retry))
                          (do
                            (warnf "[download] Unexpected error %s, giving up, context %s" error context)
                            {::error error, ::context context}))])))]
    (debugf "[download] Waiting")
    (.acquire download-semaphore)
    (infof "[download] Downloading %s" (describe context))
    (let [req (merge {:async? true,
                      :connection-manager connection-manager}
                     req (:http-options options))]
      (http/request req
       success-fn
       error-fn))))

(defn initialize-seed [seed]
  (map #(assoc % ::traverse/handler `init-handler ::traverse/call-protocol :sync)
       (ensure-seq seed)))

(def default-options
  {:max-connections 10,
   :timeout 60000,
   :retries 5,
   :conn-mgr-options {},
   :http-options {:redirect-strategy :lax, :as :auto}})

(defn initialize-options
  [options]
  (let [options (merge default-options options)]
    (assoc options
           :connection-manager (http-conn/make-reuseable-async-conn-manager (:conn-mgr-options options))
           :download-semaphore (java.util.concurrent.Semaphore. (:max-connections options)))))

(defn scrape [seed & {:as options}]
  (let [seed (initialize-seed seed)
        options (initialize-options options)]
    (traverse/leaf-seq seed options)))

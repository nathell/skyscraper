(ns skyscraper.dev
  "Tools for interactive development of scrapers. See [doc/development-mode.md]
  for an overview and example."
  (:require
    [clojure.core.async :refer [chan alts!!]]
    [clojure.java.browse :refer [browse-url]]
    [clojure.java.io :as io]
    [skyscraper.core :as core]
    [skyscraper.traverse :as traverse]
    [taoensso.timbre :as log]))

(defn- browse-context
  "Dumps the given context's response to a temporary file
  and opens a browser on it."
  [ctx]
  (let [f (java.io.File/createTempFile "skyscraper-" ".html")]
    (with-open [is (io/input-stream (get-in ctx [::core/response :body]))
                os (io/output-stream f)]
      (io/copy is os))
    (browse-url f)))

(def ^:private scrape-data (atom nil))

(defn cleanup
  "Runs a previously terminated [[scrape]] to completion."
  []
  (when-let [{{:keys [item-chan terminate-chan]} :channels, processors :processors} @scrape-data]
    (log/infof "Resuming suspended scrape to clean up")
    (core/with-processor-definitions processors
      (loop []
        (let [alts-res (alts!! [item-chan terminate-chan])
              [val port] alts-res]
          (if (= port terminate-chan)
            (reset! scrape-data nil)
            (recur)))))))

(defn scrape
  "A variant of [[skyscraper.core/scrape!]] that will stop and open a
  browser on the first encountered processor that isn't defined or doesn't
  have a process-fn."
  [seed & {:as options}]
  (cleanup)
  (let [item-chan (chan)
        options (core/initialize-options (assoc options :item-chan item-chan :parallelism 1))
        seed (core/initialize-seed options seed)
        {:keys [terminate-chan] :as channels} (traverse/launch seed options)]
    (loop []
      (let [alts-res (alts!! [item-chan terminate-chan])
            [val port] alts-res]
        (if (= port terminate-chan)
          nil
          (if-let [{:keys [::core/resource ::core/context]} (first (filter #(::core/unimplemented %) val))]
            (do (reset! scrape-data {:resource resource, :context context, :channels channels, :processors @core/processors})
                (browse-context context)
                (log/infof "Scraping suspended in processor %s" (:processor context))
                nil)
            (recur)))))))

(defn document
  "Returns the parsed document that the last invocation of [[scrape]]
  has stopped on."
  []
  (:resource @scrape-data))

(defn run-last-processor
  "Calls the processor whose invocation caused [[scrape]] to stop."
  []
  (if-let [{:keys [resource context]} @scrape-data]
    (core/run-processor (:processor context) resource context)
    (throw (ex-info "No interactive scraping in progress" {}))))

(ns skyscraper.context)

(defn dissoc-internal
  "Dissocs the context keys that shouldn't be carried over to further processing."
  [context]
  (let [removed-keys #{:processor :url :skyscraper.core/new-items :skyscraper.core/retry :skyscraper.core/prev-response
                       :skyscraper/description :skyscraper.traverse/priority}]
    (into {}
          (remove (fn [[k _]] (or (contains? removed-keys k)
                                  (and (keyword? k)
                                       (= (namespace k) "http")
                                       (not= k :http/cookies)))))
          context)))


(defn dissoc-leaf-keys
  "Dissocs the context keys that shouldn't appear in the resulting channel
  or sequence of leaf nodes."
  [context]
  (dissoc context
          :skyscraper.core/cache-key
          :skyscraper.core/current-processor
          :skyscraper.core/next-stage
          :skyscraper.core/options
          :skyscraper.core/response
          :skyscraper.core/stage
          :skyscraper.traverse/handler
          :skyscraper.traverse/call-protocol
          :skyscraper.traverse/priority
          :skyscraper/description
          :http/cookies))

(defn describe
  "Returns a user-friendly version of a context that doesn't include
  Skyscraper's internal keys, but does include the currently running
  processor name."
  [context]
  (let [processor (:skyscraper.core/current-processor context)]
    (cond-> context
      true dissoc-internal
      true dissoc-leaf-keys
      true (merge (select-keys context [:processor :url])) ; reattach
      processor (assoc :skyscraper.core/current-processor-name (:name processor))
      true pr-str)))

(defn describe-url
  [context]
  (or (:skyscraper/description context)
      (:url context)))

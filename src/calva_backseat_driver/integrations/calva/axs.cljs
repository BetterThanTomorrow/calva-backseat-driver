(ns calva-backseat-driver.integrations.calva.axs
  (:require [clojure.core.match :refer [match]]
            [clojure.walk :as walk]
            [cljs.reader :as reader]
            [datascript.core :as d]
            [calva-backseat-driver.app.db :as db]))

(def ^:private query-clause-keys
  #{:find :with :in :where :keys :strs :syms :rules})

(defn- next-query-clause-index
  [query start-index]
  (or (some (fn [index]
              (when (query-clause-keys (nth query index nil))
                index))
            (range start-index (count query)))
      (count query)))

(defn- query-clause
  [query clause-key]
  (let [start-index (.indexOf query clause-key)]
    (when (<= 0 start-index)
      (let [end-index (next-query-clause-index query (inc start-index))]
        (subvec query (inc start-index) end-index)))))

(defn- inline-query-inputs
  [query inputs]
  (let [in-index (.indexOf query :in)]
    (if (or (neg? in-index)
            (empty? inputs))
      query
      (let [in-end (next-query-clause-index query (inc in-index))
            input-specs (subvec query (inc in-index) in-end)
            [_db-symbol & params] input-specs
            replacements (zipmap params inputs)
            query-without-in (vec (concat (subvec query 0 in-index)
                                          (subvec query in-end)))]
        (walk/postwalk (fn [form]
                         (if (and (symbol? form)
                                  (contains? replacements form))
                           (get replacements form)
                           form))
                       query-without-in)))))

(defn- matching-output-log-threshold-clause
  [clause]
  (when (and (vector? clause)
             (= 1 (count clause)))
    (let [predicate-form (first clause)
          threshold (when (seq? predicate-form)
                      (nth predicate-form 2 nil))]
      (when (and (seq? predicate-form)
                 (= '> (first predicate-form))
                 (= '?l (second predicate-form))
                 (number? threshold)
                 (nil? (nth predicate-form 3 nil)))
        {:threshold threshold}))))

(defn- supported-output-log-fallback
  [runnable-query]
  (let [find-clause (query-clause runnable-query :find)
        supported-find-clause? (and (= 1 (count find-clause))
                                    (vector? (first find-clause))
                                    (let [[pull-form spread] (first find-clause)]
                                      (and (seq? pull-form)
                                           (= 'pull (first pull-form))
                                           (= '?e (second pull-form))
                                           (= '[*] (nth pull-form 2 nil))
                                           (nil? (nth pull-form 3 nil))
                                           (= '... spread))))
        where-index (.indexOf runnable-query :where)]
    (when (and supported-find-clause?
               (<= 0 where-index))
      (let [where-end (next-query-clause-index runnable-query (inc where-index))
            where-clauses (subvec runnable-query (inc where-index) where-end)
            threshold-clauses (keep-indexed (fn [index clause]
                                              (when-let [{:keys [threshold]}
                                                         (matching-output-log-threshold-clause clause)]
                                                {:index index
                                                 :threshold threshold}))
                                            where-clauses)]
        (when (= 1 (count threshold-clauses))
          (let [{:keys [index threshold]} (first threshold-clauses)
                reduced-where-clauses (vec (concat (subvec where-clauses 0 index)
                                                   (subvec where-clauses (inc index))))]
            {:threshold threshold
             :reduced-query (vec (concat (subvec runnable-query 0 (inc where-index))
                                         reduced-where-clauses
                                         (subvec runnable-query where-end)))}))))))

(defn handle-action [state _context action]
  (match action
    [:calva/ax.when-activated actions]
    {:ex/fxs [[:calva/fx.when-activated actions]]}

    [:calva/ax.subscribe-to-output]
    {:ex/fxs [[:calva/fx.subscribe-to-output [:calva/ax.add-output]]]}

    [:calva/ax.add-output message]
    (let [line (inc (:calva/output-line-counter state 0))
          entity (cond-> {:output/line line
                          :output/category (:category message)
                          :output/text (:text message)
                          :output/timestamp (js/Date.now)}
                   (:who message) (assoc :output/who (:who message))
                   (:ns message) (assoc :output/ns (:ns message))
                   (:replSessionKey message) (assoc :output/repl-session-key (:replSessionKey message)))]
      {:ex/db (assoc state :calva/output-line-counter line)
       :ex/fxs [[:calva/fx.transact-output entity]]})

    [:calva/ax.query-output query-edn-str inputs]
    (let [query (reader/read-string query-edn-str)
          conn @db/!output-conn
          runnable-query (if (seq inputs)
                           (inline-query-inputs query inputs)
                           query)
          result (try
                   (d/q runnable-query conn)
                   (catch :default e
                     (if-let [{:keys [reduced-query threshold]}
                              (supported-output-log-fallback runnable-query)]
                       (->> (d/q reduced-query conn)
                            (filterv (fn [row]
                                       (> (:output/line row) threshold))))
                       (throw e))))]
      {:ex/fxs [[:app/fx.return result]]})

    [:calva/ax.init-history]
    {:ex/fxs [[:calva/fx.load-history-from-disk (:calva/history-storage-uri state)]]}

    [:calva/ax.history-loaded max-line]
    {:ex/db (assoc state :calva/output-line-counter max-line)}

    :else
    {:ex/fxs [[:node/fx.log-error "Unknown action:" (pr-str action)]]}))

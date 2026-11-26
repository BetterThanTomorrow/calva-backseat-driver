(ns calva-backseat-driver.integrations.calva.output
  "The output log and the eval history as values: sorted maps from an entity's line to
   the entity."
  (:require
   [calva-backseat-driver.integrations.calva.values :as calva-values]
   [cljs.reader :as reader]
   [cognitect.transit :as transit]
   [datascript.core :as d]))

(def log-cap
  "The number of entities the output log holds."
  1000)

(def history-cap
  "The number of entities the eval history holds."
  10000)

(def empty-log (sorted-map))

(defn entity
  "Returns the entity for a `message` from Calva, numbered `line` and stamped `now`."
  [message line now]
  (let [shadow-build (calva-values/map-field-value message :shadowBuild)
        shadow-runtime-id (calva-values/map-field-value message :shadowRuntimeId)]
    (cond-> {:output/line line
             :output/category (:category message)
             :output/text (:text message)
             :output/timestamp now}
      (:who message) (assoc :output/who (:who message))
      (:ns message) (assoc :output/ns (:ns message))
      (:replSessionKey message) (assoc :output/repl-session-key (:replSessionKey message))
      shadow-build (assoc :output/shadow-build shadow-build)
      shadow-runtime-id (assoc :output/shadow-runtime-id shadow-runtime-id))))

(defn evaluated-code?
  "True for an entity the eval history keeps."
  [{:output/keys [category]}]
  (= "evaluatedCode" category))

(defn add
  "Adds `entity` to `log` under its line, and drops the lowest line when `log` is over `cap`."
  [log {:output/keys [line] :as entity} cap]
  (let [log (assoc (or log empty-log) line entity)]
    (cond-> log
      (> (count log) cap) (dissoc (key (first log))))))

(defn restored
  "Returns what the state holds after a restart, from the `entities` of a saved history:
   the history with all of them, the output log with the newest of them up to its cap,
   and the highest line as the line counter."
  [entities]
  (let [history (into empty-log
                      (map (juxt :output/line identity))
                      entities)
        newest-first (rseq history)]
    {:calva/history history
     :calva/output-log (into empty-log (take log-cap newest-first))
     :calva/output-line-counter (or (some-> newest-first first key) 0)}))

(defn query
  "Runs the Datalog query `query-edn-str` with `inputs` on the entities of `log`."
  [log query-edn-str inputs]
  (apply d/q
         (reader/read-string query-edn-str)
         (d/db-with (d/empty-db) (vals log))
         inputs))

(defn serialized
  "Returns the content of a history file for `history`, as a transit string."
  [history]
  (transit/write (transit/writer :json)
                 {:format-version 1
                  :entities (vec (vals history))}))

(defn deserialized
  "Returns the entities in the history file content `transit-str`. Returns nil when
   the content can't be read, or has another format version."
  [transit-str]
  (try
    (let [data (transit/read (transit/reader :json) transit-str)]
      (when (= 1 (:format-version data))
        (:entities data)))
    (catch :default _e
      nil)))

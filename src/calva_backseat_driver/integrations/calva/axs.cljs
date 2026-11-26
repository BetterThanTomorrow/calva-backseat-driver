(ns calva-backseat-driver.integrations.calva.axs
  (:require [cljs.reader :as reader]
            [datascript.core :as d]
            [calva-backseat-driver.app.db :as db]
            [calva-backseat-driver.integrations.calva.values :as calva-values]))

(defn- when-activated
  "Dispatches `actions` once Calva is active."
  [_state _uf-data actions]
  {:uf/fxs [[:uf/await :calva/fx.calva-activated]]
   :uf/dxs actions})

(defn- subscribe-to-output
  [_state _uf-data]
  {:uf/fxs [[:calva/fx.subscribe-to-output]]})

(defn- subscribe-to-sessions-changed
  [_state _uf-data]
  {:uf/fxs [[:calva/fx.subscribe-to-sessions-changed]]
   :uf/dxs [[:mcp/ax.update-registry]]})

(defn- sessions-changed
  [_state _uf-data _event]
  {:uf/dxs [[:mcp/ax.update-registry]]})

(defn- output-entity
  "Returns the output log entity for a `message` from Calva, numbered `line` and stamped `now`."
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

(defn- add-output
  "Adds a `message` from Calva to the output log."
  [state {:system/keys [now]} message]
  (let [line (inc (:calva/output-line-counter state 0))]
    {:uf/db (assoc state :calva/output-line-counter line)
     :uf/fxs [[:calva/fx.transact-output (output-entity message line now) (:calva/history-storage-uri state)]]}))

(defn- query-output
  "Runs the Datalog query `query-edn-str` with `inputs` on the output log. The dispatch resolves to the result."
  [_state _uf-data query-edn-str inputs]
  (let [query (reader/read-string query-edn-str)
        result (apply d/q query @db/!output-conn inputs)]
    {:uf/fxs [[:uf/await :app/fx.return result]]}))

(defn- init-history
  [state _uf-data]
  {:uf/fxs [[:calva/fx.load-history-from-disk (:calva/history-storage-uri state)]]})

(defn- history-loaded
  "Continues the line numbering from `max-line`, the highest line of the loaded history."
  [state _uf-data max-line]
  {:uf/db (assoc state :calva/output-line-counter max-line)})

(def actions
  {:calva/ax.when-activated #'when-activated
   :calva/ax.subscribe-to-output #'subscribe-to-output
   :calva/ax.subscribe-to-sessions-changed #'subscribe-to-sessions-changed
   :calva/ax.sessions-changed #'sessions-changed
   :calva/ax.add-output #'add-output
   :calva/ax.query-output #'query-output
   :calva/ax.init-history #'init-history
   :calva/ax.history-loaded #'history-loaded})

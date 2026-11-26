(ns calva-backseat-driver.integrations.calva.axs
  (:require
   [calva-backseat-driver.integrations.calva.output :as output]))

(def ^:private history-save-delay-ms
  "How long after the eval history changed it is saved. A change inside that time
   starts the wait again."
  500)

(defn- when-activated
  "Dispatches `actions` once Calva is active."
  [_state _uf-data actions]
  {:uf/fxs [[:uf/await :calva/fx.calva-activated]]
   :uf/dxs actions})

(defn- subscribe-to-output
  [_state _uf-data]
  {:uf/fxs [[:calva/fx.subscribe-to-output]]})

(defn- subscribe-to-sessions-changed
  "Subscribes to changes of Calva's sessions, and reads the sessions as they are now."
  [_state _uf-data]
  {:uf/fxs [[:calva/fx.subscribe-to-sessions-changed]
            [:uf/await :calva/fx.read-sessions]]
   :uf/dxs [[:calva/ax.sessions-read :uf/prev-result]]})

(defn- sessions-changed
  "Reads Calva's sessions after they changed."
  [_state _uf-data _event]
  {:uf/fxs [[:uf/await :calva/fx.read-sessions]]
   :uf/dxs [[:calva/ax.sessions-read :uf/prev-result]]})

(defn- sessions-read
  "Takes Calva's `sessions`, with their builds and runtimes, and hands them on to the
   window's registry entry."
  [state _uf-data sessions]
  {:uf/db (assoc state :calva/sessions sessions)
   :uf/dxs [[:mcp/ax.update-registry]]})

(defn- add-output
  "Adds a `message` from Calva to the output log, and to the eval history when it is
   evaluated code. A history that changed is saved shortly after."
  [state {:system/keys [now]} message]
  (let [line (inc (:calva/output-line-counter state 0))
        entity (output/entity message line now)
        code? (output/evaluated-code? entity)]
    {:uf/db (cond-> (-> state
                        (assoc :calva/output-line-counter line)
                        (update :calva/output-log output/add entity output/log-cap))
              code? (update :calva/history output/add entity output/history-cap))
     :uf/fxs (when code?
               [[:uf/fx.defer-dispatch [[:calva/ax.save-history]] history-save-delay-ms :calva/history-save]])}))

(defn- save-history
  "Saves the eval history to its file."
  [state _uf-data]
  {:uf/fxs [[:calva/fx.save-history (:calva/history-storage-uri state) (:calva/history state)]]})

(defn- init-history
  "Reads the saved eval history."
  [state _uf-data]
  {:uf/fxs [[:uf/await :calva/fx.read-history (:calva/history-storage-uri state)]]
   :uf/dxs [[:calva/ax.history-read :uf/prev-result]]})

(defn- history-read
  "Takes the `entities` of the saved eval history, nil when there is none. The history
   gets all of them, the output log the newest, and line numbering goes on from the
   highest line. A state that has taken output already keeps what it has."
  [state _uf-data entities]
  (when (zero? (:calva/output-line-counter state 0))
    {:uf/db (merge state (output/restored entities))}))

(def actions
  {:calva/ax.when-activated #'when-activated
   :calva/ax.subscribe-to-output #'subscribe-to-output
   :calva/ax.subscribe-to-sessions-changed #'subscribe-to-sessions-changed
   :calva/ax.sessions-changed #'sessions-changed
   :calva/ax.sessions-read #'sessions-read
   :calva/ax.add-output #'add-output
   :calva/ax.save-history #'save-history
   :calva/ax.init-history #'init-history
   :calva/ax.history-read #'history-read})

(ns calva-backseat-driver.integrations.calva.fxs
  (:require
   ["fs" :as fs]
   ["path" :as path]
   [datascript.core :as d]
   [calva-backseat-driver.app.db :as db]
   [calva-backseat-driver.app.live-objects :as live-objects]
   [calva-backseat-driver.integrations.calva.api :as calva]))

(defn- cap-conn! [conn max-size]
  (let [total (d/q '[:find (count ?e) . :where [?e :output/line]] @conn)]
    (when (and total (> total max-size))
      (let [excess (- total max-size)
            oldest-lines (take excess (sort (d/q '[:find [?l ...] :where [?e :output/line ?l]] @conn)))
            retractions (mapv (fn [l]
                                [:db.fn/retractEntity
                                 (d/q '[:find ?e . :in $ ?l :where [?e :output/line ?l]] @conn l)])
                              oldest-lines)]
        (d/transact! conn retractions)))))

(defonce !persist-timer (atom nil))

(defn- persist-history! [^js storage-uri]
  (when storage-uri
    (try
      (let [fs-path (.-fsPath storage-uri)
            dir-path (path/dirname fs-path)]
        (when-not (.existsSync fs dir-path)
          (.mkdirSync fs dir-path #js {:recursive true}))
        (.writeFileSync fs fs-path (db/serialize-history db/!history-conn) "utf8"))
      (catch :default e
        (js/console.error "[History] Failed to persist:" (.-message e))))))

(defn- schedule-persist! [storage-uri]
  (when-let [timer @!persist-timer]
    (js/clearTimeout timer))
  (reset! !persist-timer
          (js/setTimeout (fn []
                           (reset! !persist-timer nil)
                           (persist-history! storage-uri))
                         500)))

(defn- restore-history! [^js storage-uri]
  (let [transit-str (.readFileSync fs (.-fsPath storage-uri) "utf8")
        entities (db/deserialize-history transit-str)]
    (when (seq entities)
      (d/transact! db/!history-conn entities)
      (d/transact! db/!output-conn entities)
      (d/q '[:find (max ?l) . :where [?e :output/line ?l]] @db/!history-conn))))

(defn- calva-activated
  "Returns a promise that resolves when Calva is active."
  [_dispatch!]
  (calva/calva-activated+))

(defn- subscribe-to-output
  "Dispatches `[:calva/ax.add-output message]` for each message Calva logs."
  [dispatch!]
  (live-objects/add-disposables!
   [((get-in calva/calva-api [:repl :onOutputLogged])
     (fn [message]
       (dispatch! [[:calva/ax.add-output (js->clj message :keywordize-keys true)]])))]))

(defn- subscribe-to-sessions-changed
  "Dispatches `[:calva/ax.sessions-changed event]` when Calva's sessions or runtimes change."
  [dispatch!]
  (when-let [disposable (calva/on-sessions-changed
                         (fn [event]
                           (dispatch! [[:calva/ax.sessions-changed (js->clj event :keywordize-keys true)]])))]
    (live-objects/add-disposables! [disposable])))

(defn- transact-output
  "Adds `entity` to the output log, and to the history when it is evaluated code. The
   history is saved to `storage-uri` shortly after."
  [_dispatch! entity storage-uri]
  (d/transact! db/!output-conn [entity])
  (cap-conn! db/!output-conn 1000)
  (when (= "evaluatedCode" (:output/category entity))
    (d/transact! db/!history-conn [entity])
    (cap-conn! db/!history-conn 10000)
    (schedule-persist! storage-uri)))

(defn- load-history-from-disk
  "Loads the history at `storage-uri`, and dispatches `[:calva/ax.history-loaded max-line]`."
  [dispatch! ^js storage-uri]
  (let [loaded-line (when (and storage-uri (.existsSync fs (.-fsPath storage-uri)))
                      (try
                        (restore-history! storage-uri)
                        (catch :default e
                          (js/console.warn "[History] Failed to load, starting fresh:" (.-message e))
                          (try (.unlinkSync fs (.-fsPath storage-uri)) (catch :default _))
                          nil)))]
    (dispatch! [[:calva/ax.history-loaded (or loaded-line 0)]])))

(defn- flush-history
  "Saves the history to `storage-uri` at once."
  [_dispatch! storage-uri]
  (when-let [timer @!persist-timer]
    (js/clearTimeout timer)
    (reset! !persist-timer nil))
  (persist-history! storage-uri))

(def effects
  {:calva/fx.calva-activated #'calva-activated
   :calva/fx.subscribe-to-output #'subscribe-to-output
   :calva/fx.subscribe-to-sessions-changed #'subscribe-to-sessions-changed
   :calva/fx.transact-output #'transact-output
   :calva/fx.load-history-from-disk #'load-history-from-disk
   :calva/fx.flush-history #'flush-history})

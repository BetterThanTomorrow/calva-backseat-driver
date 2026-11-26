(ns calva-backseat-driver.integrations.calva.fxs
  (:require
   ["fs" :as fs]
   ["path" :as path]
   ["vscode" :as vscode]
   [calva-backseat-driver.app.live-objects :as live-objects]
   [calva-backseat-driver.integrations.calva.api :as calva]
   [calva-backseat-driver.integrations.calva.output :as output]))

(defn- file-path
  "The file system path of the URI string `uri`."
  [uri]
  (.-fsPath (vscode/Uri.parse uri)))

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

(defn- read-history-file
  "Returns the entities in `history-file`. A file that can't be read is removed, and
   gives nil."
  [history-file]
  (try
    (output/deserialized (fs/readFileSync history-file "utf8"))
    (catch :default e
      (js/console.warn "[History] Failed to load, starting fresh:" (.-message e))
      (try (fs/unlinkSync history-file) (catch :default _))
      nil)))

(defn- read-history
  "Returns the entities of the eval history saved at `storage-uri`, or nil when there is
   none."
  [_dispatch! storage-uri]
  (let [history-file (some-> storage-uri file-path)]
    (when (and history-file
               (fs/existsSync history-file))
      (read-history-file history-file))))

(defn- save-history
  "Writes the eval `history` to the file at `storage-uri`."
  [_dispatch! storage-uri history]
  (when storage-uri
    (try
      (let [history-file (file-path storage-uri)]
        (fs/mkdirSync (path/dirname history-file) #js {:recursive true})
        (fs/writeFileSync history-file (output/serialized history) "utf8"))
      (catch :default e
        (js/console.error "[History] Failed to persist:" (.-message e))))))

(def effects
  {:calva/fx.calva-activated #'calva-activated
   :calva/fx.subscribe-to-output #'subscribe-to-output
   :calva/fx.subscribe-to-sessions-changed #'subscribe-to-sessions-changed
   :calva/fx.read-history #'read-history
   :calva/fx.save-history #'save-history})

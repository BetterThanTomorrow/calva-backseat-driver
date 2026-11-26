(ns calva-backseat-driver.integrations.calva.fxs
  (:require
   ["fs" :as fs]
   ["path" :as path]
   ["vscode" :as vscode]
   [calva-backseat-driver.app.live-objects :as live-objects]
   [calva-backseat-driver.integrations.calva.api :as calva]
   [calva-backseat-driver.integrations.calva.features :as features]
   [calva-backseat-driver.integrations.calva.output :as output]
   [calva-backseat-driver.integrations.calva.values :as calva-values]))

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

(defn- list-sessions
  "Returns a promise of Calva's REPL sessions, each with its session key and project root."
  [_dispatch!]
  (.then (js/Promise.resolve ((get-in calva/calva-api [:repl :listSessions])))
         (fn [sessions]
           (mapv (fn [^js session]
                   {:replSessionKey (.-replSessionKey session)
                    :projectRoot (.-projectRoot session)})
                 sessions))))

(defn- list-sessions-and-runtimes
  "Returns a promise of Calva's REPL sessions with their builds and runtimes."
  [_dispatch!]
  (.then (js/Promise.resolve ((get-in calva/calva-api [:repl :listSessionsAndRuntimes])))
         (fn [sessions]
           (or (js->clj sessions :keywordize-keys true)
               []))))

(defn- evaluation
  "What Calva's `evaluation` object holds, as a map."
  [^js evaluation]
  {:evaluation/result (.-result evaluation)
   :evaluation/ns (.-ns evaluation)
   :evaluation/output (.-output evaluation)
   :evaluation/error-output (.-errorOutput evaluation)
   :evaluation/session-key (.-sessionKey evaluation)
   :evaluation/who (.-who evaluation)
   :evaluation/other-whos (some-> (.-otherWhosSinceLast evaluation) js->clj)
   :evaluation/shadow-build (calva-values/js-field-value evaluation "shadowBuild")
   :evaluation/shadow-runtime-id (calva-values/js-field-value evaluation "shadowRuntimeId")
   :evaluation/error (.-error evaluation)
   :evaluation/stacktrace (.-stacktrace evaluation)})

(defn- evaluate
  "Evaluates `code` through Calva with `options`. Returns a promise of the evaluation
   as a map, or of how Calva rejected it: `:evaluation/rejected-message` with the
   error's message, and `:evaluation/rejected-printed` with the error printed."
  [_dispatch! code options]
  (-> (js/Promise.resolve nil)
      (.then (fn [_]
               ((get-in calva/calva-api [:repl :evaluate]) code (clj->js options))))
      (.then evaluation
             (fn [^js error]
               {:evaluation/rejected-message (str (.-message error))
                :evaluation/rejected-printed (pr-str error)}))))

(defn- symbol-info
  "Returns what Calva knows about `clojure-symbol` in `ns`, as Calva gives it."
  [_dispatch! clojure-symbol repl-session-key ns]
  ((get-in calva/calva-api [:info :getSymbolInfo]) clojure-symbol repl-session-key ns))

(defn- clojuredocs
  "Returns the ClojureDocs entry for `clojure-symbol`, as Calva gives it."
  [_dispatch! clojure-symbol]
  ((get-in calva/calva-api [:info :getClojureDocsDotOrg]) clojure-symbol "user"))

(defn- load-clojure-file
  "Loads the file at `file-path` through Calva. Returns a promise of `{:load/result …}`,
   or of `{:load/error message}` when the load failed."
  [_dispatch! file-path repl-session-key who]
  (-> (js/Promise.resolve nil)
      (.then (fn [_]
               (vscode/commands.executeCommand
                "calva.loadFile"
                (cond-> #js {:path file-path :silent true}
                  repl-session-key (doto (aset "sessionKey" repl-session-key))
                  who (doto (aset "who" who))))))
      (.then (fn [result]
               {:load/result result})
             (fn [^js error]
               {:load/error (.-message error)}))))

(defn- edit-files
  "Applies the structural `edits`, with the `paddings` for finding a target and for
   the context of one that isn't found. Returns a promise of the batch's result."
  [_dispatch! edits paddings]
  (features/edit-files+ edits paddings))

(def effects
  {:calva/fx.calva-activated #'calva-activated
   :calva/fx.subscribe-to-output #'subscribe-to-output
   :calva/fx.subscribe-to-sessions-changed #'subscribe-to-sessions-changed
   :calva/fx.read-history #'read-history
   :calva/fx.save-history #'save-history
   :calva/fx.list-sessions #'list-sessions
   :calva/fx.list-sessions-and-runtimes #'list-sessions-and-runtimes
   :calva/fx.evaluate #'evaluate
   :calva/fx.symbol-info #'symbol-info
   :calva/fx.clojuredocs #'clojuredocs
   :calva/fx.load-file #'load-clojure-file
   :calva/fx.edit-files #'edit-files})

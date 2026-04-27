(ns e2e.mcp-helpers
  (:require
   ["fs" :as fs]
   ["net" :as net]
   ["path" :as path]
   ["vscode" :as vscode]
   [e2e.utils :refer [wait-for+]]
   [promesa.core :as p]))

(def workspace-uri
  (.-uri (first vscode/workspace.workspaceFolders)))

(def settings-path
  (.-fsPath (vscode/Uri.joinPath workspace-uri ".vscode" "settings.json")))

(defn connect-to-mcp-server [port]
  (p/create
   (fn [resolve reject]
     (let [socket (.connect net #js {:port port})]
       (.on socket "error" (fn [err]
                              (js/console.error "[MCP client] Socket error:" err)
                              (reject err)))
       (.on socket "connect" (fn []
                                (js/console.log "[MCP client] Connected to server port:" port)
                                (resolve socket)))))))

(defn- try-parse-json [s]
  (try (.parse js/JSON s) (catch :default _ nil)))

(defn send-request [socket request-obj]
  (p/create
   (fn [resolve _reject]
     (let [buffer (atom "")
           request-id (.-id (clj->js request-obj))
           request-str (str (.stringify js/JSON (clj->js request-obj)) "\n")
           on-data (fn on-data [data]
                     (swap! buffer str data)
                     (let [lines (.split @buffer "\n")
                           match (->> lines
                                      (keep try-parse-json)
                                      (filter #(= request-id (.-id %)))
                                      first)]
                       (when match
                         (.removeListener socket "data" on-data)
                         (resolve match))))]
       (.on socket "data" on-data)
       (.write socket request-str)))))

(defn call-tool
  "Send a tools/call request and return the parsed text content."
  [socket id tool-name arguments]
  (p/let [resp (send-request socket {:jsonrpc "2.0"
                                     :id id
                                     :method "tools/call"
                                     :params {:name tool-name
                                              :arguments arguments}})
          outer (js->clj resp :keywordize-keys true)
          text (get-in outer [:result :content 0 :text])]
    (when text
      (js->clj (.parse js/JSON text) :keywordize-keys true))))

(defn start-mcp-session!
  "Start MCP server, connect, initialize. Returns {:socket :port}."
  []
  (p/let [_ (wait-for+ #(.-isActive (vscode/extensions.getExtension "betterthantomorrow.calva-backseat-driver"))
                       :timeout 15000
                       :message "[MCP helpers] Extension not active within 15s")
          server-info+ (vscode/commands.executeCommand "calva-backseat-driver.startMcpServer")
          server-info (js->clj server-info+ :keywordize-keys true)
          port (:port server-info)
          socket (connect-to-mcp-server port)
          _ (send-request socket {:jsonrpc "2.0" :id 0 :method "initialize"})]
    {:socket socket :port port}))

(defn wait-for-tool!
  "Poll tools/list until a tool with the given name is registered."
  [socket tool-name & {:keys [timeout] :or {timeout 15000}}]
  (wait-for+ (fn []
               (p/let [resp (send-request socket {:jsonrpc "2.0" :id 999 :method "tools/list"})
                       outer (js->clj resp :keywordize-keys true)
                       tools (get-in outer [:result :tools])]
                 (some #(= tool-name (:name %)) tools)))
             :timeout timeout
             :message (str "[MCP helpers] Tool " tool-name " not registered within " timeout "ms")))

(defn stop-mcp-session! [socket]
  (p/do (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
        (.end socket)))

(def ^:private backseat-driver-setting-defaults
  {"enableMcpReplEvaluation" false
   "autoStartMCPServer" false
   "mcpSocketServerPort" 1664
   "provideBdSkill" true
   "provideEditSkill" true})

(defn- read-settings-backup [backup-path]
  (js->clj (.parse js/JSON (fs/readFileSync backup-path "utf8"))))

(defn- backed-up-setting [settings setting-key]
  (let [qualified-key (str "calva-backseat-driver." setting-key)]
    (if (contains? settings qualified-key)
      {:setting/value (get settings qualified-key)}
      {:setting/absent? true})))

(defn- restored-js-value [setting]
  (if (:setting/absent? setting)
    js/undefined
    (:setting/value setting)))

(defn- restored-effective-value [setting default-value]
  (if (:setting/absent? setting)
    default-value
    (:setting/value setting)))

(defn backup-settings! [backup-name]
  (let [backup-path (path/join (path/dirname settings-path) backup-name)]
    (fs/copyFileSync settings-path backup-path)
    backup-path))

(defn restore-settings! [backup-path]
  (let [settings (read-settings-backup backup-path)
        restored-settings (into {}
                                (map (fn [[setting-key _default-value]]
                                       [setting-key (backed-up-setting settings setting-key)]))
                                backseat-driver-setting-defaults)
        config (vscode/workspace.getConfiguration "calva-backseat-driver")]
    (p/let [_ (.update config "enableMcpReplEvaluation" (restored-js-value (get restored-settings "enableMcpReplEvaluation")) vscode/ConfigurationTarget.Workspace)
            _ (.update config "autoStartMCPServer" (restored-js-value (get restored-settings "autoStartMCPServer")) vscode/ConfigurationTarget.Workspace)
            _ (.update config "mcpSocketServerPort" (restored-js-value (get restored-settings "mcpSocketServerPort")) vscode/ConfigurationTarget.Workspace)
            _ (.update config "provideBdSkill" (restored-js-value (get restored-settings "provideBdSkill")) vscode/ConfigurationTarget.Workspace)
            _ (.update config "provideEditSkill" (restored-js-value (get restored-settings "provideEditSkill")) vscode/ConfigurationTarget.Workspace)
            _ (wait-for+ #(let [current-config (vscode/workspace.getConfiguration "calva-backseat-driver")]
                            (every? (fn [[setting-key default-value]]
                                      (= (restored-effective-value (get restored-settings setting-key) default-value)
                                         (.get current-config setting-key)))
                                    backseat-driver-setting-defaults))
                         :timeout 15000
                         :message "[MCP helpers] Settings restore not propagated within 15s")]
      (when (fs/existsSync backup-path)
        (fs/unlinkSync backup-path))
      nil)))

(defn ensure-repl-and-eval-enabled!
  []
  (p/let [_ (vscode/commands.executeCommand "calva.startJoyrideReplAndConnect")
          _ (wait-for+ #(some? (.currentSessionKey (.-repl (.-v1 (.-exports (vscode/extensions.getExtension "betterthantomorrow.calva"))))))
                       :timeout 15000
                       :message "[MCP helpers] REPL session not available within 15s")
          config (vscode/workspace.getConfiguration "calva-backseat-driver")
          _ (.update config "enableMcpReplEvaluation" true vscode/ConfigurationTarget.Workspace)
          _ (wait-for+ #(true? (.get (vscode/workspace.getConfiguration "calva-backseat-driver")
                                     "enableMcpReplEvaluation"))
                       :timeout 15000
                       :message "[MCP helpers] Config enableMcpReplEvaluation not propagated within 15s")]
    nil))

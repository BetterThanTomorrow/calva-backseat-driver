(ns e2e.mcp-helpers
  (:require
   ["child_process" :as child-process]
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

(def default-mcp-host "127.0.0.1")

(defn connect-to-mcp-server
  ([port] (connect-to-mcp-server port default-mcp-host))
  ([port host]
   (p/create
    (fn [resolve reject]
      (let [socket (.connect net #js {:port port :host host})]
       (.on socket "error" (fn [err]
                              (js/console.error "[MCP client] Socket error:" err)
                              (reject err)))
       (.on socket "connect" (fn []
                                (js/console.log "[MCP client] Connected to server on" host "port:" port)
                                (resolve socket))))))))

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

(defn send-request-with-timeout
  "Send a JSON-RPC request and reject if no matching response within timeout-ms."
  [socket request-obj timeout-ms]
  (let [request-id (.-id (clj->js request-obj))]
    (p/race
     [(send-request socket request-obj)
      (p/let [_ (p/delay timeout-ms)]
        (p/rejected
         (js/Error.
          (str "Request " request-id " timed out after " timeout-ms "ms"))))])))

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
          ;; Commands register via an async effect chain after activation, so
          ;; isActive can be true before the command exists. Wait for it.
          _ (wait-for+ #(p/let [cmds (vscode/commands.getCommands true)]
                          (.includes cmds "calva-backseat-driver.startMcpServer"))
                       :timeout 15000
                       :message "[MCP helpers] startMcpServer command not registered within 15s")
          server-info+ (vscode/commands.executeCommand "calva-backseat-driver.startMcpServer")
          server-info (js->clj server-info+ :keywordize-keys true)
          port (or (:server/assigned-port server-info) (:assigned-port server-info))
          host (or (:server/host server-info) (:host server-info) default-mcp-host)
          _ (when (or (nil? port) (js/isNaN port) (<= port 0))
              (throw (js/Error. (str "[MCP helpers] Invalid MCP port after start: " port
                                     " server-info: " (pr-str server-info)))))
          socket (connect-to-mcp-server port host)
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
  {"enableMcpReplEvaluation" true
   "autoStartMCPServer" false
   "mcpSocketServerPort" 1664
   "mcpHost" "127.0.0.1"
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
            _ (.update config "mcpHost" (restored-js-value (get restored-settings "mcpHost")) vscode/ConfigurationTarget.Workspace)
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

(defn wrapper-script-path
  "Absolute path to the stdio MCP wrapper script bundled with the extension."
  []
  (let [^js ext (vscode/extensions.getExtension "betterthantomorrow.calva-backseat-driver")
        script (path/join (.. ext -extensionUri -fsPath) "dist" "calva-mcp-server.js")]
    (when-not (.existsSync fs script)
      (throw (js/Error. (str "Stdio wrapper script not found at " script))))
    script))

(defn spawn-stdio-wrapper!
  "Spawn the stdio MCP wrapper relay connected to port. Returns a client map."
  ([port] (spawn-stdio-wrapper! port default-mcp-host))
  ([port host]
   (p/create
    (fn [resolve reject]
      (try
        (let [script (wrapper-script-path)
              proc (.spawn child-process "node" #js [script (str port) host] #js {:stdio #js ["pipe" "pipe" "pipe"]})
             stdin (.-stdin proc)
             stdout (.-stdout proc)
             stderr (.-stderr proc)
             buffer (atom "")]
         (.setEncoding stdout "utf8")
         (.setEncoding stderr "utf8")
         (.on stderr "data"
              (fn [chunk]
                (js/console.error "[stdio wrapper stderr]" chunk)))
         (.on proc "error" reject)
         (resolve {:proc proc :stdin stdin :stdout stdout :buffer buffer}))
        (catch :default err
          (reject err)))))))

(defn send-stdio-request!
  "Send a JSON-RPC request through the stdio wrapper and wait for a matching id."
  [{:keys [stdin stdout buffer] :as _client} request & {:keys [timeout] :or {timeout 10000}}]
  (let [request-id (.-id (clj->js request))
        request-str (str (.stringify js/JSON (clj->js request)) "\n")]
    (p/race
     [(p/create
       (fn [resolve _reject]
         (let [on-data (fn on-data [data]
                         (swap! buffer str data)
                         (let [lines (.split @buffer "\n")
                               match (->> lines
                                          (keep try-parse-json)
                                          (filter #(= request-id (.-id %)))
                                          first)]
                           (when match
                             (.removeListener stdout "data" on-data)
                             (resolve match))))]
           (.on stdout "data" on-data)
           (.write stdin request-str))))
      (p/let [_ (p/delay timeout)]
        (p/rejected
         (js/Error.
          (str "Stdio request " request-id " timed out after " timeout "ms"))))])))

(defn stop-stdio-wrapper! [{:keys [proc]}]
  (when proc
    (.kill proc)))

(defn start-chunked-response-server!
  "Start a fake TCP server that replies to the first request by splitting
   response-json across two socket writes. Used to simulate TCP chunking."
  [response-obj & {:keys [chunk-size] :or {chunk-size 1024}}]
  (p/create
   (fn [resolve _reject]
     (let [full-response (str (.stringify js/JSON (clj->js response-obj)) "\n")
           responded? (atom false)
           server (.createServer
                   net
                   (fn [^js socket]
                     (.setEncoding socket "utf8")
                     (.on socket "data"
                          (fn [_data]
                            (when-not @responded?
                              (reset! responded? true)
                              (let [split-at (min chunk-size (.-length full-response))
                                    part1 (subs full-response 0 split-at)
                                    part2 (subs full-response split-at)]
                                (.write socket part1)
                                (.write socket part2)))))))]
       (.listen server 0 "127.0.0.1"
                (fn []
                  (let [^js address (.address server)]
                    (resolve {:server server
                              :port (.-port address)
                              :response-length (.-length full-response)}))))))))

(defn stop-chunked-response-server! [{:keys [server]}]
  (when server
    (.close server)))

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

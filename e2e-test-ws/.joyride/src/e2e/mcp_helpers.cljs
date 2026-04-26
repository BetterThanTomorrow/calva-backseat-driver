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

(defn stop-mcp-session! [socket]
  (p/do (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
        (.end socket)))

(defn backup-settings! [backup-name]
  (let [backup-path (path/join (path/dirname settings-path) backup-name)]
    (fs/copyFileSync settings-path backup-path)
    backup-path))

(defn restore-settings! [backup-path]
  (fs/renameSync backup-path settings-path))

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



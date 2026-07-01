(ns calva-backseat-driver.mcp.server
  (:require
   ["os" :as os]
   ["path" :as path]
   ["vscode" :as vscode]
   [vscode-mcp.server :as btt-mcp]
   [promesa.core :as p]))

(defonce ^:private current-server-info (atom nil))

(defn- get-workspace-root-uri-or-nil []
  (some-> vscode/workspace.workspaceFolders
          first
          .-uri))

(defn- get-server-dir+ [ctx-or-base-uri]
  (let [base (cond
               (instance? vscode/Uri ctx-or-base-uri) ctx-or-base-uri
               (get-workspace-root-uri-or-nil) (get-workspace-root-uri-or-nil)
               :else (.-globalStorageUri ^js ctx-or-base-uri))]
    (vscode/Uri.joinPath base ".calva" "mcp-server")))

(defn- get-port-file-uri+ [ctx-or-base-uri]
  (vscode/Uri.joinPath (get-server-dir+ ctx-or-base-uri) "port"))

(defn random-anon-id []
  (str "anon-" (subs (str (random-uuid)) 0 8)))

(defn- cursor-unique-id [workspace-root-path-or-nil storage-uri-path-or-nil]
  (cond
    workspace-root-path-or-nil
    (str "ws-" (hash workspace-root-path-or-nil))

    storage-uri-path-or-nil
    (str "win-" (hash storage-uri-path-or-nil))

    :else
    (random-anon-id)))

(defn- get-cursor-port-file-uri [_wrapper-config-path workspace-root-uri storage-uri]
  (let [unique-id (cursor-unique-id (some-> workspace-root-uri .-fsPath)
                                    (some-> storage-uri .-fsPath))
        port-file-path (.join path (os/tmpdir) "calva-mcp-server" unique-id "port")]
    (vscode/Uri.file port-file-path)))

(defn- mcp-on-log-fn [dispatch!]
  (fn [level & args]
    (dispatch! [[:app/ax.log level (apply str (interpose " " args))]])))

(defn- resolve-stop-server-info [info {:keys [server/instance server/port-file-uri ex/dispatch!]}]
  (let [resolved-instance (or instance (:server/instance info))
        resolved-port-file-uri (or port-file-uri (:server/port-file-uri info))]
    (merge info
           {:server/instance resolved-instance
            :server/port-file-uri resolved-port-file-uri
            :mcp/on-log (mcp-on-log-fn dispatch!)})))

(defn start-server!+
  "Creates a socket server and writes the port to a file.
   Returns a promise that resolves to a map with server info when the MCP server starts successfully."
  [{:ex/keys [dispatch!] :keys [server/request-port server/host vscode/extension-context mcp/use-global-port-file? mcp/wrapper-config-path]}]
  (let [^js port-file-uri (if use-global-port-file?
                            (get-cursor-port-file-uri wrapper-config-path
                                                      (get-workspace-root-uri-or-nil)
                                                      (some-> ^js extension-context .-storageUri))
                            (get-port-file-uri+ extension-context))]
    (-> (btt-mcp/start-server!+
         {:server/request-port request-port
          :server/host host
          :server/port-file-uri port-file-uri
          :mcp/on-log (mcp-on-log-fn dispatch!)
          :mcp/on-request (fn [req]
                            (dispatch! [[:mcp/ax.handle-request req]]))})
        (p/then (fn [server-info]
                  (reset! current-server-info server-info)
                  server-info)))))

(defn stop-server!+
  "Stops the MCP server and removes the port file.
   Returns a promise that resolves to a boolean indicating success."
  [{:keys [ex/dispatch!] :as options}]
  (let [info @current-server-info]
    (if-not info
      (do
        (dispatch! [[:app/ax.log :info "No server instance to stop."]])
        (p/resolved false))
      (-> (btt-mcp/stop-server!+ (resolve-stop-server-info info options))
          (p/then (fn [res]
                    (reset! current-server-info nil)
                    res))))))

(defn send-notification-params [{:ex/keys [dispatch!]} notification]
  (if-let [info @current-server-info]
    (btt-mcp/send-notification-params
     (assoc info :mcp/on-log (mcp-on-log-fn dispatch!))
     notification)
    (dispatch! [[:app/ax.log :warn "Cannot send notification, no server running."]])))

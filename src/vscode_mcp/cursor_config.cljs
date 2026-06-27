(ns vscode-mcp.cursor-config
  (:require
   ["path" :as path]))

(defn mcp-client-identifier
  "Cursor MCP service identifier for `mcp.reloadClient`.
   Built as user-{extensionId}-extension-{registerServer name}"
  [^js extension-context server-name]
  (when extension-context
    (let [extension-id (some-> extension-context .-extension .-id)]
      (when (seq extension-id)
        (str "user-" extension-id "-extension-" server-name)))))

(defn wrapper-script-path
  "Absolute path to the stdio wrapper bundled with the running extension instance."
  [^js extension-context script-relative-path]
  (when extension-context
    (path/join (.-extensionPath extension-context) script-relative-path)))

(defn build-stdio-server-config
  [server-name wrapper-path port-file-path]
  (cond
    (not (seq (str wrapper-path)))
    {:ok false :reason :missing-wrapper-path}

    (not (seq (str port-file-path)))
    {:ok false :reason :missing-port-file-path}

    :else
    {:ok true
     :config {:name server-name
              :server {:command "node"
                       :args [(str wrapper-path) (str port-file-path)]
                       :env {}}}}))

(defn build-cursor-mcp-registration-config
  [server-name extension-context script-relative-path port-file-uri]
  (let [port-file-fs-path (some-> port-file-uri (unchecked-get "fsPath"))]
    (build-stdio-server-config server-name
                               (wrapper-script-path extension-context script-relative-path)
                               port-file-fs-path)))

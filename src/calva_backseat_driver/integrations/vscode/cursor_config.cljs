(ns calva-backseat-driver.integrations.vscode.cursor-config
  (:require
   ["path" :as path]))

(def cursor-mcp-server-name "backseat-driver")

(defn wrapper-script-path
  "Absolute path to the stdio wrapper bundled with the running extension instance."
  [^js extension-context]
  (when extension-context
    (path/join (.-extensionPath extension-context) "dist" "calva-mcp-server.js")))

(defn port-file-fs-path [server-info]
  (some-> server-info :server/port-file-uri .-fsPath))

(defn build-stdio-server-config
  "Pure config builder. Returns {:ok true :config ...} or {:ok false :reason ...}."
  [wrapper-path port-file-path]
  (cond
    (not (seq (str wrapper-path)))
    {:ok false :reason :missing-wrapper-path}

    (not (seq (str port-file-path)))
    {:ok false :reason :missing-port-file-path}

    :else
    {:ok true
     :config {:name cursor-mcp-server-name
              :server {:command "node"
                       :args [(str wrapper-path) (str port-file-path)]
                       :env {}}}}))

(defn build-cursor-mcp-registration-config
  [extension-context server-info]
  (build-stdio-server-config (wrapper-script-path extension-context)
                             (port-file-fs-path server-info)))

(defn should-auto-start-mcp-server?
  "True when the existing autoStart setting is on, or Cursor auto-register is enabled and available."
  [auto-start-mcp? auto-register-cursor-mcp? cursor-mcp-available?]
  (or auto-start-mcp?
      (and auto-register-cursor-mcp? cursor-mcp-available?)))

(defn should-register-cursor-mcp?
  [auto-register-cursor-mcp? cursor-mcp-available? server-info]
  (and auto-register-cursor-mcp?
       cursor-mcp-available?
       (seq (port-file-fs-path server-info))))

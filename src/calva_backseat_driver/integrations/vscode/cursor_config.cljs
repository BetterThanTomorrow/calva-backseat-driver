(ns calva-backseat-driver.integrations.vscode.cursor-config
  (:require
   [btt.mcp.cursor-config :as btt-cursor-config]))

(def cursor-mcp-server-name "backseat-driver")
(def cursor-mcp-extension-segment "extension")
(def cursor-mcp-reload-client-command-id "mcp.reloadClient")

(defn cursor-mcp-settings-display-name []
  (str cursor-mcp-extension-segment "-" cursor-mcp-server-name))

(defn mcp-client-identifier [extension-context]
  (btt-cursor-config/mcp-client-identifier extension-context cursor-mcp-server-name))

(defn wrapper-script-path [extension-context]
  (btt-cursor-config/wrapper-script-path extension-context "dist/calva-mcp-server.js"))

(defn port-file-fs-path [server-info]
  (some-> server-info :server/port-file-uri (unchecked-get "fsPath")))

(defn build-stdio-server-config [wrapper-path port-file-path]
  (btt-cursor-config/build-stdio-server-config cursor-mcp-server-name wrapper-path port-file-path))

(defn build-cursor-mcp-registration-config [extension-context server-info]
  (btt-cursor-config/build-cursor-mcp-registration-config
   cursor-mcp-server-name
   extension-context
   "dist/calva-mcp-server.js"
   (:server/port-file-uri server-info)))

(defn should-auto-start-mcp-server? [auto-start-mcp? auto-register-cursor-mcp? cursor-mcp-available?]
  (or auto-start-mcp?
      (and auto-register-cursor-mcp? cursor-mcp-available?)))

(defn should-register-cursor-mcp? [auto-register-cursor-mcp? cursor-mcp-available? server-info]
  (and auto-register-cursor-mcp?
       cursor-mcp-available?
       (seq (port-file-fs-path server-info))))

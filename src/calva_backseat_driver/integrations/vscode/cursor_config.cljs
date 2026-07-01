(ns calva-backseat-driver.integrations.vscode.cursor-config
  (:require
   [vscode-mcp.cursor-config :as btt-cursor-config]
   [vscode-mcp.policy :as mcp-policy]))

(def cursor-mcp-server-name "backseat-driver")
(def cursor-mcp-extension-segment "extension")

(defn cursor-mcp-settings-display-name []
  (str cursor-mcp-extension-segment "-" cursor-mcp-server-name))

(defn mcp-client-identifier [extension-context]
  (btt-cursor-config/mcp-client-identifier
   {:vscode/extension-context extension-context
    :cursor/server-name cursor-mcp-server-name}))

(defn wrapper-script-path [extension-context]
  (btt-cursor-config/wrapper-script-path
   {:vscode/extension-context extension-context
    :cursor/script-relative-path "dist/calva-mcp-server.js"}))

(defn port-file-fs-path [server-info]
  (some-> server-info :server/port-file-uri (unchecked-get "fsPath")))

(defn build-stdio-server-config [wrapper-path port-file-path host]
  (btt-cursor-config/build-stdio-server-config
   {:cursor/server-name cursor-mcp-server-name
    :cursor/wrapper-path wrapper-path
    :server/port-file-path port-file-path
    :server/host host}))

(defn build-cursor-mcp-registration-config [extension-context server-info]
  (btt-cursor-config/build-cursor-mcp-registration-config
   {:cursor/server-name cursor-mcp-server-name
    :vscode/extension-context extension-context
    :cursor/script-relative-path "dist/calva-mcp-server.js"
    :server/port-file-uri (:server/port-file-uri server-info)
    :server/host (:server/host server-info)}))

(defn should-auto-start-mcp-server? [auto-start-mcp? auto-register-cursor-mcp? cursor-mcp-available?]
  (mcp-policy/should-auto-start? {:mcp/auto-start? auto-start-mcp?
                                  :mcp/auto-register? auto-register-cursor-mcp?
                                  :mcp/cursor-available? cursor-mcp-available?}))

(defn should-register-cursor-mcp? [auto-register-cursor-mcp? cursor-mcp-available? server-info]
  (mcp-policy/should-register-with-cursor? {:mcp/auto-register? auto-register-cursor-mcp?
                                            :mcp/cursor-available? cursor-mcp-available?
                                            :mcp/port-file-present? (boolean (seq (port-file-fs-path server-info)))}))

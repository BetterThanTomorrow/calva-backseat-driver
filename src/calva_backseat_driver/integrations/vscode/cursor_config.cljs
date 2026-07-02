(ns calva-backseat-driver.integrations.vscode.cursor-config
  (:require
   [vscode-mcp.policy :as mcp-policy]))

(def cursor-mcp-base-server-name "backseat-driver")
(def cursor-mcp-extension-segment "extension")

(defn cursor-mcp-server-name
  "Cursor `registerServer` name: the base name suffixed with the per-window
   instance slug so windows don't overwrite each other's registrations."
  [instance-slug]
  (str cursor-mcp-base-server-name "-" instance-slug))

(defn cursor-mcp-settings-display-name [server-name]
  (str cursor-mcp-extension-segment "-" server-name))

(defn port-file-fs-path [server-info]
  (some-> server-info :server/port-file-uri (unchecked-get "fsPath")))

(defn should-auto-start-mcp-server? [auto-start-mcp? auto-register-cursor-mcp? cursor-mcp-available?]
  (mcp-policy/should-auto-start? {:mcp/auto-start? auto-start-mcp?
                                  :mcp/auto-register? auto-register-cursor-mcp?
                                  :mcp/cursor-available? cursor-mcp-available?}))

(defn should-register-cursor-mcp? [auto-register-cursor-mcp? cursor-mcp-available? server-info]
  (mcp-policy/should-register-with-cursor? {:mcp/auto-register? auto-register-cursor-mcp?
                                            :mcp/cursor-available? cursor-mcp-available?
                                            :mcp/port-file-present? (boolean (seq (port-file-fs-path server-info)))}))

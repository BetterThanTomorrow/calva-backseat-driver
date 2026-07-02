(ns calva-backseat-driver.integrations.vscode.cursor
  (:require
   [vscode-mcp.cursor :as btt-cursor]
   [calva-backseat-driver.integrations.vscode.cursor-config :as config]))

(defn cursor-mcp-available? []
  (btt-cursor/cursor-mcp-available?))

(defn register-and-reload-mcp-client!+ [extension-context server-info]
  (btt-cursor/register-and-reload-mcp-client!+
   {:cursor/server-name config/cursor-mcp-server-name
    :vscode/extension-context extension-context
    :cursor/script-relative-path "dist/calva-mcp-server.js"
    :server/port-file-uri (:server/port-file-uri server-info)
    :server/host (:server/host server-info)}))

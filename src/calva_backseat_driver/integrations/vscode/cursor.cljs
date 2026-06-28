(ns calva-backseat-driver.integrations.vscode.cursor
  (:require
   [vscode-mcp.cursor :as btt-cursor]
   [calva-backseat-driver.integrations.vscode.cursor-config :as config]))

(defn cursor-mcp-available? []
  (btt-cursor/cursor-mcp-available?))

(defn register-and-reload-mcp-client!+ [extension-context server-info]
  (btt-cursor/register-and-reload-mcp-client!+ config/cursor-mcp-server-name extension-context "dist/calva-mcp-server.js" (:server/port-file-uri server-info)))

(defn unregister-mcp-server!+ []
  (btt-cursor/unregister-mcp-server!+ config/cursor-mcp-server-name))

(ns calva-backseat-driver.integrations.vscode.cursor
  (:require
   [btt.mcp.cursor :as btt-cursor]
   [calva-backseat-driver.integrations.vscode.cursor-config :as config]))

(defn cursor-mcp-available? []
  (btt-cursor/cursor-mcp-available?))

(defn port-file-ready?+ [server-info]
  (btt-cursor/port-file-ready?+ (:server/port-file-uri server-info)))

(defn reload-mcp-client!+ [extension-context]
  (btt-cursor/reload-mcp-client!+ extension-context config/cursor-mcp-server-name))

(defn register-mcp-server!+ [extension-context server-info]
  (btt-cursor/register-mcp-server!+ config/cursor-mcp-server-name extension-context "dist/calva-mcp-server.js" (:server/port-file-uri server-info)))

(defn register-and-reload-mcp-client!+ [extension-context server-info]
  (btt-cursor/register-and-reload-mcp-client!+ config/cursor-mcp-server-name extension-context "dist/calva-mcp-server.js" (:server/port-file-uri server-info)))

(defn unregister-mcp-server!+ []
  (btt-cursor/unregister-mcp-server!+ config/cursor-mcp-server-name))

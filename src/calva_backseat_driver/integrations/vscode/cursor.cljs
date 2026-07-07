(ns calva-backseat-driver.integrations.vscode.cursor
  (:require
   [vscode-mcp.cursor :as btt-cursor]))

(defn cursor-mcp-available? []
  (btt-cursor/cursor-mcp-available?))

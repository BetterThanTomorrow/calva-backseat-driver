(ns calva-backseat-driver.integrations.vscode.cursor
  "Thin BD-shaped wrapper around `vscode-mcp.cursor`.

   Used only by the dev-only shadow-cljs hot-reload path (`extension.cljs`'s
   `after-load`) to re-assert Cursor registration for an already-running
   server — `vscode-mcp.core`'s `start!+`/`maybe-start!+` no-op once
   running, so this is the one place BD calls `vscode-mcp.cursor` directly
   instead of through `vscode-mcp.core`. Production start/stop/registration
   goes through `mcp/fxs.cljs`'s `lifecycle-start`/`lifecycle-stop` effects."
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

(ns calva-backseat-driver.mcp.fxs
  (:require
   [calva-backseat-driver.app.live-objects :as live-objects]
   [calva-backseat-driver.mcp.requests :as requests]
   [vscode-mcp.core :as vscode-mcp]))

(defn- lifecycle-config
  "Returns the config vscode-mcp runs the MCP server from. vscode-mcp keeps the server
   and reports its state, as plain data, through `:mcp/on-server-changed`."
  [dispatch! {:mcp/keys [wrapper-config-path settings registry-data]}]
  (vscode-mcp/create-config
   {:vscode/extension-context (live-objects/extension-context)
    :server/name "calva-backseat-driver"
    :wrapper/script-relative-path "dist/calva-mcp-server.js"
    :lifecycle/wrapper-install-dir wrapper-config-path
    :manual-setup/extension-name "Backseat Driver"
    :mcp/auto-start? (:vscode/config.autoStartMCPServer settings)
    :mcp/auto-register? (:vscode/config.autoRegisterCursorMcp settings)
    :mcp/auto-register-eca? (:vscode/config.autoRegisterEcaMcp settings)
    :server/host (:vscode/config.mcpHost settings)
    :server/request-port (:vscode/config.mcpSocketServerPort settings)
    :registry/enabled? (not (false? (:vscode/config.enableMcpRegistry settings)))
    :registry/custom-data registry-data
    :mcp/on-request (fn [request]
                      (dispatch! [[:mcp/ax.handle-request request]]))
    :mcp/on-log (fn [level & args]
                  (dispatch! [[:app/ax.log level (apply str (interpose " " args))]]))
    :mcp/on-server-changed (fn [server-state]
                             (dispatch! [[:mcp/ax.server-changed server-state]]))}))

(defn- lifecycle-start
  "Starts the MCP server, silently and only when the settings ask for it with
   `:lifecycle/silent?`. Returns a promise of the server's state."
  [dispatch! {:lifecycle/keys [silent?] :as options}]
  (let [config (lifecycle-config dispatch! options)]
    (if silent?
      (vscode-mcp/maybe-start!+ config)
      (vscode-mcp/start!+ config))))

(defn- lifecycle-stop
  "Stops the MCP server. Returns a promise of the server's state."
  [dispatch! {:lifecycle/keys [silent?] :as options}]
  (vscode-mcp/stop!+ (lifecycle-config dispatch! options)
                     {:lifecycle/silent? (boolean silent?)}))

(defn- register-with-cursor
  "Registers the MCP server with Cursor, starting it when needed. Returns a promise of
   the server's state."
  [dispatch! options]
  (vscode-mcp/register-with-cursor!+ (lifecycle-config dispatch! options)))

(defn- handle-request
  "Answers the MCP `request`. Returns the response, or a promise of it."
  [dispatch! options request]
  (requests/handle-request-fn (assoc options :uf/dispatch! dispatch!)
                              request))

(defn- update-registry
  "Refreshes the window's registry entry."
  [dispatch! options]
  (vscode-mcp/update-registry!+ (lifecycle-config dispatch! options)))

(def effects
  {:mcp/fx.lifecycle-start #'lifecycle-start
   :mcp/fx.lifecycle-stop #'lifecycle-stop
   :mcp/fx.register-with-cursor #'register-with-cursor
   :mcp/fx.handle-request #'handle-request
   :mcp/fx.update-registry #'update-registry})

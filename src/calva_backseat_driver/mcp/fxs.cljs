(ns calva-backseat-driver.mcp.fxs
  (:require
   [calva-backseat-driver.app.live-objects :as live-objects]
   [calva-backseat-driver.mcp.requests :as requests]
   [calva-backseat-driver.mcp.server :as server]
   [vscode-mcp.core :as vscode-mcp]))

(defn- lifecycle-config
  [dispatch! {:mcp/keys [wrapper-config-path settings]}]
  (server/build-lifecycle-config dispatch! (live-objects/extension-context) wrapper-config-path settings))

(defn- lifecycle-start
  "Starts the MCP server. Returns a promise of the new lifecycle state."
  [dispatch! {:lifecycle/keys [silent?]
              :mcp/keys [lifecycle-state]
              :as options}]
  (let [start!+ (if silent? vscode-mcp/maybe-start!+ vscode-mcp/start!+)]
    (start!+ (lifecycle-config dispatch! options) lifecycle-state silent?)))

(defn- lifecycle-stop
  "Stops the MCP server. Returns a promise of the new lifecycle state."
  [dispatch! {:lifecycle/keys [silent?]
              :mcp/keys [lifecycle-state]
              :as options}]
  (-> (js/Promise.resolve
       (vscode-mcp/stop!+ (lifecycle-config dispatch! options) lifecycle-state {:lifecycle/silent? (boolean silent?)}))
      (.catch (fn [e]
                (dispatch! [[:mcp/ax.server-error (str e)]])
                (vscode-mcp/init-state)))))

(defn- register-with-cursor
  "Registers the MCP server with Cursor, starting it when needed. Returns a promise of
   what the registration gave: `:ok` and the new lifecycle `:state`, a `:reason`, or the
   `:error` message when it threw."
  [dispatch! {:mcp/keys [lifecycle-state] :as options}]
  (-> (js/Promise.resolve
       (vscode-mcp/register-with-cursor!+ (lifecycle-config dispatch! options) lifecycle-state))
      (.catch (fn [e]
                {:ok false
                 :error (ex-message e)}))))

(defn- handle-request
  "Answers the MCP `request`. Returns the response, or a promise of it."
  [dispatch! options request]
  (requests/handle-request-fn (assoc options
                                     :uf/dispatch! dispatch!
                                     :vscode/extension-context (live-objects/extension-context))
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

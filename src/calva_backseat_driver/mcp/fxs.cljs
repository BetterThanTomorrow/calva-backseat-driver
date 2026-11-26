(ns calva-backseat-driver.mcp.fxs
  (:require
   [calva-backseat-driver.app.live-objects :as live-objects]
   [calva-backseat-driver.mcp.requests :as requests]
   [calva-backseat-driver.mcp.server :as server]
   [vscode-mcp.core :as vscode-mcp]))

(defn- lifecycle-config
  [dispatch! {:mcp/keys [wrapper-config-path settings]}]
  (server/build-lifecycle-config dispatch! (live-objects/extension-context) wrapper-config-path settings))

(defn- lifecycle-state
  "The lifecycle state vscode-mcp handed back last, which holds the running server."
  []
  (or (live-objects/object :mcp/lifecycle-state)
      (vscode-mcp/init-state)))

(defn- server-state
  "What the app state holds about the server, from vscode-mcp's `lifecycle-state`."
  [lifecycle-state]
  (let [{:server/keys [assigned-port host]} (vscode-mcp/server-info lifecycle-state)]
    {:server/running? (vscode-mcp/running? lifecycle-state)
     :server/assigned-port assigned-port
     :server/host host
     :lifecycle/registered-name (:lifecycle/registered-name lifecycle-state)}))

(defn- keep-lifecycle-state!
  "Keeps `lifecycle-state` among the live objects, and returns the server's state from it."
  [lifecycle-state]
  (live-objects/put! :mcp/lifecycle-state lifecycle-state)
  (server-state lifecycle-state))

(defn- lifecycle-start
  "Starts the MCP server. Returns a promise of the server's state."
  [dispatch! {:lifecycle/keys [silent?]
              :as options}]
  (let [start!+ (if silent? vscode-mcp/maybe-start!+ vscode-mcp/start!+)]
    (.then (js/Promise.resolve (start!+ (lifecycle-config dispatch! options) (lifecycle-state) silent?))
           keep-lifecycle-state!)))

(defn- lifecycle-stop
  "Stops the MCP server. Returns a promise of the server's state."
  [dispatch! {:lifecycle/keys [silent?]
              :as options}]
  (-> (js/Promise.resolve
       (vscode-mcp/stop!+ (lifecycle-config dispatch! options) (lifecycle-state) {:lifecycle/silent? (boolean silent?)}))
      (.catch (fn [e]
                (dispatch! [[:mcp/ax.server-error (str e)]])
                (vscode-mcp/init-state)))
      (.then keep-lifecycle-state!)))

(defn- register-with-cursor
  "Registers the MCP server with Cursor, starting it when needed. Returns a promise of
   what the registration gave: `:ok` or a `:reason` with the server's state as
   `:server/state`, or the `:error` message when it threw."
  [dispatch! options]
  (-> (js/Promise.resolve
       (vscode-mcp/register-with-cursor!+ (lifecycle-config dispatch! options) (lifecycle-state)))
      (.then (fn [{lifecycle-state :state :as result}]
               (-> result
                   (dissoc :state)
                   (assoc :server/state (keep-lifecycle-state! lifecycle-state)))))
      (.catch (fn [e]
                {:ok false
                 :error (or (ex-message e) (str e))}))))

(defn- server-info
  "Returns what the start command resolves to: the running server's info as a
   JavaScript object, or nil when no server runs."
  [_dispatch!]
  (clj->js (vscode-mcp/server-info (lifecycle-state))))

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
   :mcp/fx.server-info #'server-info
   :mcp/fx.handle-request #'handle-request
   :mcp/fx.update-registry #'update-registry})

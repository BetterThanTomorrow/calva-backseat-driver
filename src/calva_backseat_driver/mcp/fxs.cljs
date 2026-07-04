(ns calva-backseat-driver.mcp.fxs
  (:require
   ["fs" :as fs]
   ["path" :as path]
   ["vscode" :as vscode]
   [calva-backseat-driver.ex.ax :as ax]
   [calva-backseat-driver.integrations.vscode.cursor :as cursor]
   [calva-backseat-driver.mcp.requests :as requests]
   [calva-backseat-driver.mcp.server :as server]
   [cljs.core.match :refer [match]]
   [promesa.core :as p]
   [vscode-mcp.core :as vscode-mcp]
   [vscode-mcp.server :as btt-mcp-server]))

(defn- copy-wrapper-script! [wrapper-config-path]
  (let [extension-uri (-> (vscode/extensions.getExtension
                           "betterthantomorrow.calva-backseat-driver")
                          .-extensionUri)
        script-uri (vscode/Uri.joinPath extension-uri "dist" "calva-mcp-server.js")
        script-path (.-fsPath script-uri)
        dest-path (path/join wrapper-config-path "calva-mcp-server.js")]
    (fs/mkdirSync wrapper-config-path #js {:recursive true})
    (try (fs/unlinkSync dest-path) (catch :default _e))
    (if js/goog.DEBUG
      (try
        (fs/symlinkSync script-path dest-path)
        (catch :default _e))
      (fs/copyFileSync script-path dest-path))))

(defn perform-effect! [dispatch! ^js context effect]
  (match effect
    [:mcp/fx.lifecycle-start options]
    (let [{:ex/keys [on-success]
           :lifecycle/keys [silent?]
           :mcp/keys [wrapper-config-path lifecycle-state]} options
          config (server/build-lifecycle-config dispatch! context wrapper-config-path)
          start!+ (if silent? vscode-mcp/maybe-start!+ vscode-mcp/start!+)]
      (p/then (start!+ config lifecycle-state silent?)
              (fn [new-lifecycle-state]
                (dispatch! context (ax/enrich-with-args on-success new-lifecycle-state)))))

    [:mcp/fx.lifecycle-stop options]
    (let [{:ex/keys [on-success]
           :mcp/keys [wrapper-config-path lifecycle-state]
           :lifecycle/keys [silent?]
           :cursor/keys [unregister?]
           :or {silent? false unregister? true}} options
          config (server/build-lifecycle-config dispatch! context wrapper-config-path)]
      (-> (vscode-mcp/stop!+ config lifecycle-state {:lifecycle/silent? silent?
                                                     :cursor/unregister? unregister?})
          (p/then (fn [new-lifecycle-state]
                    (dispatch! context (ax/enrich-with-args on-success new-lifecycle-state))))
          (p/catch (fn [e]
                     (dispatch! context [[:mcp/ax.server-error e]])
                     (dispatch! context (ax/enrich-with-args on-success (vscode-mcp/init-state)))))))

    [:mcp/fx.register-with-cursor options]
    (let [{:ex/keys [on-success on-error]
           :mcp/keys [wrapper-config-path lifecycle-state]} options
          config (server/build-lifecycle-config dispatch! context wrapper-config-path)]
      (-> (vscode-mcp/register-or-start-with-cursor!+ config lifecycle-state)
          (p/then (fn [result]
                    (if (:ok result)
                      (dispatch! context (ax/enrich-with-args on-success (:state result)))
                      (do
                        (dispatch! context [[:vscode/fx.show-information-message
                                             (case (:reason result)
                                               :server-not-running "Start the MCP server first, or disable auto-register and use Register to start and register in one step."
                                               :cursor-api-unavailable "Cursor MCP registration API is not available in this editor."
                                               :already-registered "Backseat Driver MCP server is already registered with Cursor."
                                               "Could not register Backseat Driver MCP server with Cursor.")]])
                        (dispatch! context (ax/enrich-with-args on-error result))))))
          (p/catch (fn [e]
                     (dispatch! context [[:app/ax.log :error "[Cursor MCP] register-with-cursor error:" e]])
                     (dispatch! context (ax/enrich-with-args on-error e))))))

    [:mcp/fx.register-cursor-mcp-server server-info options]
    (let [{:ex/keys [on-success on-error]} options]
      (-> (cursor/register-and-reload-mcp-client!+ context server-info)
          (p/then (fn [result]
                    (cond
                      (not (:ok result))
                      (do
                        (dispatch! context [[:app/ax.log :warn "[Cursor MCP] registerServer failed:" result]])
                        (dispatch! context (ax/enrich-with-args on-error result)))

                      (and (:reload result) (not (:ok (:reload result))))
                      (do
                        (dispatch! context [[:app/ax.log :warn "[Cursor MCP] reloadClient failed:" (:reload result)]])
                        (dispatch! context (ax/enrich-with-args on-success result)))

                      :else
                      (dispatch! context (ax/enrich-with-args on-success result)))))
          (p/catch (fn [e]
                     (dispatch! context [[:app/ax.log :error "[Cursor MCP] registerServer error:" e]])
                     (dispatch! context (ax/enrich-with-args on-error e))))))

    [:mcp/fx.send-notification server-info notification]
    (btt-mcp-server/send-notification-params server-info notification)

    [:mcp/fx.handle-request options request]
    (requests/handle-request-fn (assoc options :ex/dispatch! (partial dispatch! context)
                                       :vscode/extension-context context) request)

    [:mcp/fx.copy-wrapper-script-to-config-dir wrapper-config-path]
    (copy-wrapper-script! wrapper-config-path)

    :else
    (js/console.warn "Unknown MCP effect:" (pr-str effect))))

(ns calva-backseat-driver.mcp.fxs
  (:require
   [calva-backseat-driver.ex.ax :as ax]
   [calva-backseat-driver.mcp.requests :as requests]
   [calva-backseat-driver.mcp.server :as server]
   [cljs.core.match :refer [match]]
   [promesa.core :as p]
   [vscode-mcp.core :as vscode-mcp]
   [vscode-mcp.server :as mcp-server]))

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
           :or {silent? false}} options
          config (server/build-lifecycle-config dispatch! context wrapper-config-path)]
      (-> (vscode-mcp/stop!+ config lifecycle-state {:lifecycle/silent? silent?})
          (p/then (fn [new-lifecycle-state]
                    (dispatch! context (ax/enrich-with-args on-success new-lifecycle-state))))
          (p/catch (fn [e]
                     (dispatch! context [[:mcp/ax.server-error e]])
                     (dispatch! context (ax/enrich-with-args on-success (vscode-mcp/init-state)))))))

    [:mcp/fx.register-with-cursor options]
    (let [{:ex/keys [on-success on-error]
           :mcp/keys [wrapper-config-path lifecycle-state]} options
          config (server/build-lifecycle-config dispatch! context wrapper-config-path)]
      (-> (vscode-mcp/register-with-cursor!+ config lifecycle-state)
          (p/then (fn [result]
                    (if (:ok result)
                      (dispatch! context (ax/enrich-with-args on-success (:state result)))
                      (do
                        (dispatch! context [[:vscode/fx.show-information-message
                                             (case (:reason result)
                                               :cursor-api-unavailable "Cursor MCP registration API is not available in this editor."
                                               "Could not register Backseat Driver MCP server with Cursor.")]])
                        (dispatch! context (ax/enrich-with-args on-error result))))))
          (p/catch (fn [e]
                     (dispatch! context [[:app/ax.log :error "[Cursor MCP] register-with-cursor error:" e]])
                     (dispatch! context (ax/enrich-with-args on-error e))))))

    [:mcp/fx.send-notification server-info notification]
    (mcp-server/send-notification-params server-info notification)

    [:mcp/fx.handle-request options request]
    (requests/handle-request-fn (assoc options :ex/dispatch! (partial dispatch! context)
                                       :vscode/extension-context context) request)

    :else
    (js/console.warn "Unknown MCP effect:" (pr-str effect))))

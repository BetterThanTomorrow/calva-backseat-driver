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
   [promesa.core :as p]))

(defn- show-server-started-message! [server-info wrapper-config-path]
  (let [{:server/keys [assigned-port ^js port-file-uri port-note]} server-info
        script-path (path/join wrapper-config-path "calva-mcp-server.js")]
    (p/let [button (vscode/window.showInformationMessage
                    (str port-note " MCP socket server started on port: " assigned-port
                         ". Now your MCP client can run the `calva` stdio server command."
                         " (See Backseat Driver README and the docs of your AI Agent for how to do this.)")
                    "Copy command + port"
                    "Copy command + port-file")]
      (case button
        "Copy command + port"
        (vscode/env.clipboard.writeText
         (str "node " script-path " " assigned-port))

        "Copy command + port-file"
        (let [port-file-path (.-fsPath port-file-uri)]
          (vscode/env.clipboard.writeText
           (str "node " script-path " " port-file-path)))

        nil))))

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
    [:mcp/fx.start-server options]
    (let [{:ex/keys [on-success on-error]} options]
      (-> (server/start-server!+ (assoc options :ex/dispatch!
                                        (partial dispatch! context)
                                        :vscode/extension-context context))
          (p/then (fn [server-info]
                    (dispatch! context (ax/enrich-with-args on-success server-info))))
          (p/catch
           (fn [e]
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

    [:mcp/fx.stop-server options]
    (let [{:ex/keys [on-success on-error]
           :keys [mcp/cursor-registered?]} options
          stop-server+ (fn []
                         (p/catch
                          (server/stop-server!+ (assoc options :ex/dispatch!
                                                       (partial dispatch! context)
                                                       :vscode/extension-context context))
                          (fn [e]
                            (dispatch! context (ax/enrich-with-args on-error (.-message e)))
                            false)))]
      (-> (if cursor-registered?
            (-> (cursor/unregister-mcp-server!+)
                (p/then (fn [result]
                          (when-not (:ok result)
                            (let [err (:error result)]
                              (when (or (nil? err) (not= "Canceled" (.-name err)))
                                (dispatch! context [[:app/ax.log :debug "[Cursor MCP] unregisterServer:" result]]))))
                          result)))
            (p/resolved true))
          (p/then (fn [_] (stop-server+)))
          (p/then (fn [success?]
                    (when cursor-registered?
                      (dispatch! context [[:mcp/ax.cursor-mcp-unregistered {:ok true}]]))
                    (dispatch! context (ax/enrich-with-args on-success success?))))))

    [:mcp/fx.show-server-started-message server-info wrapper-config-path]
    (show-server-started-message! server-info wrapper-config-path)

    [:mcp/fx.send-notification notification]
    (server/send-notification-params {:ex/dispatch! (partial dispatch! context)} notification)

    [:mcp/fx.handle-request options request]
    (requests/handle-request-fn (assoc options :ex/dispatch! (partial dispatch! context)
                                       :vscode/extension-context context) request)

    [:mcp/fx.copy-wrapper-script-to-config-dir wrapper-config-path]
    (copy-wrapper-script! wrapper-config-path)

    :else
    (js/console.warn "Unknown MCP effect:" (pr-str effect))))
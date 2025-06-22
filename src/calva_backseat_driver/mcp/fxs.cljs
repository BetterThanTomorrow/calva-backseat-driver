(ns calva-backseat-driver.mcp.fxs
  (:require
   ["fs" :as fs]
   ["path" :as path]
   ["vscode" :as vscode]
   [calva-backseat-driver.ex.ax :as ax]
   [calva-backseat-driver.mcp.requests :as requests]
   [calva-backseat-driver.mcp.server :as server]
   [cljs.core.match :refer [match]]
   [promesa.core :as p]))

(defn perform-effect! [dispatch! ^js context effect]
  (match effect
    [:mcp/fx.start-server options]
    (let [{:ex/keys [on-success on-error]} options]
      (-> (server/start-server!+ (assoc options :ex/dispatch!
                                        (partial dispatch! context)))
          (p/then (fn [server-info]
                    (dispatch! context (ax/enrich-with-args on-success server-info))))
          (p/catch
           (fn [e]
             (dispatch! context (ax/enrich-with-args on-error e))))))

    [:mcp/fx.stop-server options]
    (let [{:ex/keys [on-success on-error]} options]
      (-> (p/catch
           (server/stop-server!+ (assoc options :ex/dispatch!
                                        (partial dispatch! context)))
           (fn [e]
             (dispatch! context (ax/enrich-with-args on-error (.-message e)))))
          (p/then (fn [success?]
                    (dispatch! context (ax/enrich-with-args on-success success?))))))

    [:mcp/fx.show-server-started-message server-info wrapper-config-path]
    (let [{:server/keys [port ^js port-file-uri port-note]} server-info
          script-path (path/join wrapper-config-path "calva-mcp-server.js")]
      (p/let [button (vscode/window.showInformationMessage (str port-note " MCP socket server started on port: " port ". Now your MCP client can run the `calva` stdio server command. (See Backseat Driver README and the docs of your AI Agent for how to do this.)") "Copy command + port" "Copy command + port-file")]
        (case button
          "Copy command + port"
          (vscode/env.clipboard.writeText
           (str "node " script-path " " port))

          "Copy command + port-file"
          (let [port-file-path (.-fsPath port-file-uri)]
            (vscode/env.clipboard.writeText
             (str "node " script-path " " port-file-path)))

          nil)))

    [:mcp/fx.send-notification notification]
    (server/send-notification-params {:ex/dispatch! (partial dispatch! context)} notification)

    [:mcp/fx.handle-request options request]
    (requests/handle-request-fn (assoc options :ex/dispatch! (partial dispatch! context)) request)

    [:mcp/fx.copy-wrapper-script-to-config-dir wrapper-config-path]
    (let [extension-uri (-> (vscode/extensions.getExtension
                             "betterthantomorrow.calva-backseat-driver")
                            .-extensionUri)
          script-uri (vscode/Uri.joinPath extension-uri "dist" "calva-mcp-server.js")
          script-path (.-fsPath script-uri)]
      (fs/mkdirSync wrapper-config-path
                    #js {:recursive true}
                    (fn [err]
                      (when err (throw err))
                      (js/console.log "Directories created successfully")))
      ((if js/goog.DEBUG
         fs/symlinkSync
         fs/copyFileSync) script-path (path/join wrapper-config-path "calva-mcp-server.js")))

    :else
    (js/console.warn "Unknown MCP effect:" (pr-str effect))))
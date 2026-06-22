(ns calva-backseat-driver.integrations.vscode.cursor
  (:require
   ["fs" :as fs]
   ["vscode" :as vscode]
   [calva-backseat-driver.integrations.vscode.cursor-config :as config]
   [promesa.core :as p]))

(defn cursor-mcp-available? []
  (boolean
   (and (some? (.-cursor vscode))
        (some? (.-mcp (.-cursor vscode)))
        (fn? (.-registerServer (.-mcp (.-cursor vscode)))))))

(defn port-file-ready?+ [server-info]
  (p/let [path (config/port-file-fs-path server-info)
          uri (when (seq path) (vscode/Uri.file path))]
    (if-not uri
      false
      (-> (vscode/workspace.fs.stat uri)
          (p/then (fn [_] true))
          (p/catch (fn [_] false))))))

(defn reload-mcp-client!+ [^js extension-context]
  (let [identifier (config/mcp-client-identifier extension-context)]
    (cond
      (not identifier)
      (p/resolved {:ok false :reason :missing-extension-context})

      :else
      (-> (vscode/commands.executeCommand
           config/cursor-mcp-reload-client-command-id
           (clj->js {:identifier identifier}))
          (p/then (fn [result] {:ok true :identifier identifier :result result}))
          (p/catch (fn [err] {:ok false :identifier identifier :error err}))))))

(defn register-mcp-server!+ [^js extension-context server-info]
  (p/let [{:keys [ok config reason]} (config/build-cursor-mcp-registration-config extension-context server-info)
          ready? (port-file-ready?+ server-info)]
    (cond
      (not ok)
      (p/resolved {:ok false :reason reason})

      (not (cursor-mcp-available?))
      (p/resolved {:ok false :reason :cursor-api-unavailable})

      (not ready?)
      (p/resolved {:ok false :reason :port-file-not-ready})

      :else
      (-> (.registerServer (.-mcp (.-cursor vscode)) (clj->js config))
          (p/then (fn [_] {:ok true :config config}))
          (p/catch (fn [err] {:ok false :error err :config config}))))))

(defn register-and-reload-mcp-client!+ [^js extension-context server-info]
  (p/let [register-result (register-mcp-server!+ extension-context server-info)]
    (if-not (:ok register-result)
      register-result
      (p/let [reload-result (reload-mcp-client!+ extension-context)]
        (assoc register-result :reload reload-result)))))

(defn unregister-mcp-server!+ []
  (cond
    (not (cursor-mcp-available?))
    (p/resolved {:ok false :reason :cursor-api-unavailable})

    :else
    (-> (.unregisterServer (.-mcp (.-cursor vscode)) config/cursor-mcp-server-name)
        (p/then (fn [_] {:ok true}))
        (p/catch (fn [err] {:ok false :error err})))))

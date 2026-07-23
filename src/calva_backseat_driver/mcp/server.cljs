(ns calva-backseat-driver.mcp.server
  (:require
   ["os" :as os]
   ["path" :as path]
   ["vscode" :as vscode]
   [vscode-mcp.core :as vscode-mcp]))

(defn- get-workspace-root-uri-or-nil []
  (some-> vscode/workspace.workspaceFolders
          first
          .-uri))

(defn- get-server-dir+ [ctx-or-base-uri]
  (let [base (cond
               (instance? vscode/Uri ctx-or-base-uri) ctx-or-base-uri
               (get-workspace-root-uri-or-nil) (get-workspace-root-uri-or-nil)
               :else (.-globalStorageUri ^js ctx-or-base-uri))]
    (vscode/Uri.joinPath base ".calva" "mcp-server")))

(defn- get-port-file-uri+ [ctx-or-base-uri]
  (vscode/Uri.joinPath (get-server-dir+ ctx-or-base-uri) "port"))

(defn- get-cursor-port-file-uri [instance-slug]
  (vscode/Uri.file (path/join (os/tmpdir) "calva-mcp-server" instance-slug "port")))

(defn build-lifecycle-config
  "Builds a `vscode-mcp.core` config from current settings and BD's
   port-file/wrapper-install-dir/when-context conventions. Cheap to rebuild — callers
   don't need to cache it (see plan Decision Q6: settings are read fresh on
   each start/stop, same as the rest of BD's Ex config-keyword enrichment)."
  [dispatch! ^js context wrapper-config-path]
  (let [settings (vscode/workspace.getConfiguration "calva-backseat-driver")]
    (vscode-mcp/create-config
     {:vscode/extension-context context
      :cursor/server-name "backseat-driver"
      :cursor/script-relative-path "dist/calva-mcp-server.js"
      :mcp/auto-start? (.get settings "autoStartMCPServer")
      :mcp/auto-register? (.get settings "autoRegisterCursorMcp")
      :mcp/auto-register-eca? (.get settings "autoRegisterEcaMcp")
      :manual-setup/extension-name "Backseat Driver"
      :server/host (.get settings "mcpHost")
      :mcp/on-request (fn [request]
                        (dispatch! context [[:mcp/ax.handle-request request]]))
      :mcp/on-log (fn [level & args]
                    (dispatch! context [[:app/ax.log level (apply str (interpose " " args))]]))
      :lifecycle/port-file-uri+ (fn [^js ctx {:lifecycle/keys [cursor-mode? instance-slug]}]
                                  (if cursor-mode?
                                    (get-cursor-port-file-uri instance-slug)
                                    (get-port-file-uri+ ctx)))
      :lifecycle/eca-port-file-uri+ (fn [^js ctx _strategy-opts]
                                      (get-port-file-uri+ ctx))
      :lifecycle/request-port (fn [_ctx {:lifecycle/keys [cursor-mode?]}]
                                (if cursor-mode? 0 (.get settings "mcpSocketServerPort")))
      :lifecycle/wrapper-install-dir wrapper-config-path
      :lifecycle/on-starting-changed (fn [starting?]
                                       (dispatch! context [[:app/ax.set-when-context :calva-backseat-driver/starting? starting?]]))
      :lifecycle/on-stopping-changed (fn [stopping?]
                                       (dispatch! context [[:app/ax.set-when-context :calva-backseat-driver/stopping? stopping?]]))
      :lifecycle/on-running-changed (fn [running? _server-info]
                                      (dispatch! context [[:app/ax.set-when-context :calva-backseat-driver/started? running?]
                                                          [:mcp/ax.sync-cursor-mcp-when-contexts]]))
      :lifecycle/on-cursor-registered (fn [result]
                                        (dispatch! context [[:mcp/ax.cursor-mcp-registered result]]))
      :lifecycle/on-cursor-registration-failed (fn [failure]
                                                 (dispatch! context [[:mcp/ax.cursor-mcp-registration-failed failure]]))
      :lifecycle/on-error (fn [err]
                            (dispatch! context [[:mcp/ax.server-error err]]))})))

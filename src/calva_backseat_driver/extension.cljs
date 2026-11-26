(ns calva-backseat-driver.extension
  (:require
   ["os" :as os]
   ["path" :as path]
   ["vscode" :as vscode]
   [calva-backseat-driver.app.dispatch :as dispatch]
   [calva-backseat-driver.app.live-objects :as live-objects]
   [calva-backseat-driver.integrations.calva.api :as calva-api]
   [calva-backseat-driver.integrations.calva.version :as version]
   [calva-backseat-driver.integrations.vscode.settings :as settings]
   [vscode-mcp.cursor :as vscode-mcp-cursor]
   [vscode-mcp.eca :as vscode-mcp-eca]
   [vscode-mcp.manifest :as manifest]))

(defn- mcp-availability
  "What the MCP server's start by itself depends on: the editors and extensions it can
   register with, and an open workspace folder."
  []
  {:mcp/cursor-mcp-available? (vscode-mcp-cursor/cursor-mcp-available?)
   :mcp/eca-available? (vscode-mcp-eca/eca-available?)
   :mcp/workspace-root-present? (vscode-mcp-eca/workspace-root-present?)})

(defn- initial-state
  "The state the extension starts with: the settings, what is available, the manifest
   as data, and where its files are, as URI strings."
  [^js context]
  (merge (settings/settings context)
         (mcp-availability)
         {:extension/manifest (manifest/manifest-data context)
          :app/log-file-uri (str (vscode/Uri.joinPath (.-logUri context) "mcp-server.log"))
          :app/min-log-level :debug
          :mcp/wrapper-config-path (path/join (os/homedir) ".config" "calva" "backseat-driver")
          :calva/history-storage-uri (some-> (.-storageUri context)
                                             (vscode/Uri.joinPath "eval-history.transit.json")
                                             str)}))

(def ^:private min-backseat-driver-version "0.0.33")
(def ^:private min-calva-version "2.0.592")

(defn ^:export activate [^js context]
  (js/console.time "activation")
  (js/console.timeLog "activation" "Calva Backseat Driver activate START")
  (let [calva-version (calva-api/calva-version)
        parsed-calva (version/parse-version calva-version)
        parsed-min (version/parse-version min-calva-version)]
    (when-not (version/version>= parsed-calva parsed-min)
      (throw (js/Error. (str "Backseat Driver >= " min-backseat-driver-version
                             " requires Calva >= " min-calva-version
                             ". Found: " (or calva-version "unknown"))))))

  (live-objects/put! :vscode/extension-context context)
  (dispatch/dispatch! [[:app/ax.activate (initial-state context)]])

  (js/console.timeLog "activation" "Calva Backseat Driver activate END")
  (js/console.timeEnd "activation")
  #js {:v1 {}})

(comment
  (some-> vscode
          .-workspace
          (.getConfiguration "calva-backseat-driver")
          (.get "enableMcpREPLEvaluation"))
  :rcf)

(defn ^:export deactivate
  "Returns a promise that resolves when the history is saved and the MCP server has stopped."
  []
  (dispatch/dispatch! [[:app/ax.deactivate]]))

(comment
  (dispatch/dispatch! [[:app/ax.cleanup]])
  (activate (live-objects/extension-context))
  @dispatch/!dispatch-log
  :rcf)

;;;;; shadow-cljs hot reload hooks
;; We don't need to do anything here, but it is nice to see that reloading is happening

(defn ^{:dev/before-load true
        :export true}
  before-load []
  (println "shadow-cljs reloading..."))

(defn ^{:dev/after-load true
        :export true}
  after-load []
  (println "shadow-cljs reload complete")
  (when (live-objects/extension-context)
    (dispatch/dispatch! [[:app/ax.hot-reloaded (mcp-availability)]])))
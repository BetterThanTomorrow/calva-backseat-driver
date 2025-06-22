(ns calva-backseat-driver.extension
  (:require
   ["os" :as os]
   ["path" :as path]
   ["vscode" :as vscode]
   [calva-backseat-driver.ex.ex :as ex]
   [calva-backseat-driver.app.db :as db]))

(defn- extension-context []
  (:vscode/extension-context @db/!app-db))

(defn- initial-state [^js context]
  {:app/log-file-uri
   (vscode/Uri.joinPath
    (.-logUri context) "mcp-server.log")
   :app/min-log-level :debug
   :mcp/wrapper-config-path (path/join (os/homedir) ".config" "calva" "backseat-driver")})

(defn ^:export activate [^js context]
  (js/console.time "activation")
  (js/console.timeLog "activation" "Calva Backseat Driver activate START")

  (when-not (extension-context)
    (swap! db/!app-db assoc
           :vscode/extension-context context
           :app/getConfiguration vscode/workspace.getConfiguration))
  (ex/dispatch! context [[:app/ax.activate (initial-state context)]])

  (js/console.timeLog "activation" "Calva Backseat Driver activate END")
  (js/console.timeEnd "activation")
  #js {:v1 {}})

(comment
  (some-> vscode
          .-workspace
          (.getConfiguration "calva-backseat-driver")
          (.get "enableMcpREPLEvaluation"))
  :rcf)

(defn ^:export deactivate []
  (ex/dispatch! (extension-context) [[:app/ax.deactivate]]))

(comment
  (ex/dispatch! (extension-context) [[:app/ax.cleanup]])
  (activate (extension-context))
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
  (println "shadow-cljs reload complete"))
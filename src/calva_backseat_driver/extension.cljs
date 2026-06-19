(ns calva-backseat-driver.extension
  (:require
   ["os" :as os]
   ["path" :as path]
   ["vscode" :as vscode]
   [calva-backseat-driver.ex.ex :as ex]
   [calva-backseat-driver.app.db :as db]
   [calva-backseat-driver.integrations.calva.api :as calva-api]
   [calva-backseat-driver.integrations.calva.version :as version]))

(defn- extension-context []
  (:vscode/extension-context @db/!app-db))

(defn- initial-state [^js context]
  {:app/log-file-uri
   (vscode/Uri.joinPath
    (.-logUri context) "mcp-server.log")
   :app/min-log-level :debug
   :mcp/wrapper-config-path (path/join (os/homedir) ".config" "calva" "backseat-driver")
   :calva/history-storage-uri (some-> (.-storageUri context)
                                      (vscode/Uri.joinPath "eval-history.transit.json"))})

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
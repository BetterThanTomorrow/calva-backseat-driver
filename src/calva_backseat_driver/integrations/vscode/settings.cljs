(ns calva-backseat-driver.integrations.vscode.settings
  "Reads the extension's settings into the map the app state holds them in."
  (:require
   ["vscode" :as vscode]))

(def section "calva-backseat-driver")

(defn- setting-names
  "The names of the settings in the manifest's configuration, without the section."
  [^js extension-context]
  (let [properties (.. extension-context -extension -packageJSON -contributes -configuration -properties)]
    (map #(subs % (inc (count section)))
         (js/Object.keys properties))))

(defn settings
  "Returns every setting the manifest declares, as `{:vscode/config.<name> value}`."
  [extension-context]
  (let [^js config (vscode/workspace.getConfiguration section)]
    (into {}
          (map (fn [setting-name]
                 [(keyword "vscode" (str "config." setting-name))
                  (.get config setting-name)]))
          (setting-names extension-context))))

(defn when-clause-settings
  "Returns what the when clauses of the manifest's tools and skills evaluate to, by
   clause, from the settings in `state`."
  [state]
  {"config.calva-backseat-driver.enableMcpReplEvaluation" (true? (:vscode/config.enableMcpReplEvaluation state))
   ":calva-mcp-extension/activated?" true})

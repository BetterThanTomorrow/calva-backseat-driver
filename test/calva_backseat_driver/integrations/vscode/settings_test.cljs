(ns calva-backseat-driver.integrations.vscode.settings-test
  (:require ["fs" :as fs]
            ["path" :as path]
            [cljs.test :refer [deftest is]]
            [calva-backseat-driver.integrations.vscode.settings :as settings]))

(deftest settings
  (let [extension-context #js {:extension #js {:packageJSON (js/JSON.parse (fs/readFileSync (path/resolve "package.json") "utf8"))}}]
    (is (= #{:vscode/config.enableMcpReplEvaluation
             :vscode/config.autoStartMCPServer
             :vscode/config.autoRegisterCursorMcp
             :vscode/config.autoRegisterEcaMcp
             :vscode/config.enableMcpRegistry
             :vscode/config.mcpSocketServerPort
             :vscode/config.mcpHost
             :vscode/config.editor.fuzzyLineTargetingPadding
             :vscode/config.editor.lineContextResponsePadding
             :vscode/config.evaluation.maxLength
             :vscode/config.evaluation.maxDepth}
           (set (keys (settings/settings extension-context))))
        "A key for every setting in package.json")))

(deftest when-clause-settings
  (is (= {"config.calva-backseat-driver.enableMcpReplEvaluation" true
          ":calva-mcp-extension/activated?" true}
         (settings/when-clause-settings {:vscode/config.enableMcpReplEvaluation true})))
  (is (false? (get (settings/when-clause-settings {})
                   "config.calva-backseat-driver.enableMcpReplEvaluation"))
      "Evaluation is off unless its setting is true"))

(ns calva-backseat-driver.mcp.skills-test
  (:require [cljs.test :refer [deftest is]]
            ["fs" :as fs]
            ["path" :as path]
            [calva-backseat-driver.integrations.vscode.settings :as settings]
            [vscode-mcp.manifest :as manifest]))

(def package-json
  (js/JSON.parse (fs/readFileSync (path/resolve "package.json") "utf8")))

(def mock-context
  "The extension's manifest as data."
  (manifest/manifest-data
   #js {:extensionPath (js/process.cwd)
        :extension #js {:packageJSON package-json}}))

(deftest skills-provision-test
  (let [skills (manifest/get-resources mock-context {:settings (settings/when-clause-settings {})})]
    (is (= #{"backseat-driver" "editing-clojure-files"} (set (map :name skills)))
        "Both skills are provided, whatever the settings")
    (is (every? #(.endsWith (:uri %) "/SKILL.md") skills)))
  (is (not-any? #(contains? % "when") (:manifest/skills mock-context))
      "No skill has a when clause"))

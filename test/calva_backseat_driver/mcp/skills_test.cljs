(ns calva-backseat-driver.mcp.skills-test
  (:require [cljs.test :refer [deftest testing is]]
            ["fs" :as fs]
            ["path" :as path]
            [vscode-mcp.manifest :as manifest]))

(def package-json
  (js/JSON.parse (fs/readFileSync (path/resolve "package.json") "utf8")))

(def mock-context
  #js {:extensionPath (js/process.cwd)
       :extension #js {:packageJSON package-json}})

(defn settings-map [{:mcp/keys [provide-bd-skill? provide-edit-skill?]}]
  {"config.calva-backseat-driver.provideBdSkill" provide-bd-skill?
   "config.calva-backseat-driver.provideEditSkill" provide-edit-skill?
   ":calva-mcp-extension/activated?" true})

(defn get-skills [options]
  (manifest/get-resources mock-context {:settings (settings-map options)}))

(deftest skills-provision-test
  (testing "when both skills are enabled"
    (let [skills (get-skills {:mcp/provide-bd-skill? true
                              :mcp/provide-edit-skill? true})]
      (is (= 2 (count skills)))
      (is (some #(= "backseat-driver" (:name %)) skills))
      (is (some #(= "editing-clojure-files" (:name %)) skills))))

  (testing "when only backseat-driver is enabled"
    (let [skills (get-skills {:mcp/provide-bd-skill? true
                              :mcp/provide-edit-skill? false})]
      (is (= 1 (count skills)))
      (is (= "backseat-driver" (:name (first skills))))))

  (testing "when only editing-clojure-files is enabled"
    (let [skills (get-skills {:mcp/provide-bd-skill? false
                              :mcp/provide-edit-skill? true})]
      (is (= 1 (count skills)))
      (is (= "editing-clojure-files" (:name (first skills))))))

  (testing "when both skills are disabled"
    (let [skills (get-skills {:mcp/provide-bd-skill? false
                              :mcp/provide-edit-skill? false})]
      (is (empty? skills)))))

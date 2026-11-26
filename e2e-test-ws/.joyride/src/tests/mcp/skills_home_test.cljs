(ns tests.mcp.skills-home-test
  "A server start installs Backseat Driver's skills into the skills home,
  `~/.config/vscode-mcp/skills/calva-backseat-driver/`, with their reference files."
  (:require
   ["fs" :as fs]
   ["os" :as os]
   ["path" :as path]
   ["vscode" :as vscode]
   [cljs.test :refer [is testing]]
   [e2e.macros :refer [deftest-async]]
   [e2e.mcp-helpers :as mcp]))

(def ^:private extension-skills-dir
  (path/join (os/homedir) ".config" "vscode-mcp" "skills" "calva-backseat-driver"))

(defn- put-in-place-ms
  "When the entry at `p` was last put in place, from its status change time."
  [p]
  (.-ctimeMs (fs/lstatSync p)))

(deftest-async skills-installed-at-start
  (testing "A server start puts the skills in the skills home"
    (try
      (let [_ (await (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer"))
            started-ms (- (js/Date.now) 1000)
            {:keys [socket]} (await (mcp/start-mcp-session!))
            bd-skill (path/join extension-skills-dir "backseat-driver")
            edit-skill (path/join extension-skills-dir "editing-clojure-files")]
        (is (fs/existsSync (path/join bd-skill "SKILL.md"))
            "The backseat-driver skill is in the skills home")
        (is (re-find #"(?m)^name: backseat-driver$" (fs/readFileSync (path/join bd-skill "SKILL.md") "utf8"))
            "It is the backseat-driver skill")
        (is (fs/existsSync (path/join bd-skill "references" "output-log-queries.md"))
            "The skill has its reference file")
        (is (fs/existsSync (path/join edit-skill "SKILL.md"))
            "The editing-clojure-files skill is in the skills home")
        (is (<= started-ms (put-in-place-ms bd-skill))
            "The start put the skill in place")
        (await (mcp/stop-mcp-session! socket)))
      (catch :default e
        (js/console.error "[skills-home] Error:" (.-message e) e)
        (await (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer"))
        (throw e)))))

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

(defn- remove-entry!
  "Removes the entry at `p`, when there is one: a link by unlinking it, so that what it
  points at is left."
  [p]
  (when-let [stat (try (fs/lstatSync p) (catch :default _ nil))]
    (if (.isSymbolicLink stat)
      (fs/unlinkSync p)
      (fs/rmSync p #js {:recursive true}))))

(deftest-async skills-installed-at-start
  (testing "A server start puts the skills in the skills home"
    (try
      (let [bd-skill (path/join extension-skills-dir "backseat-driver")
            edit-skill (path/join extension-skills-dir "editing-clojure-files")
            _ (await (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer"))
            _ (remove-entry! bd-skill)
            _ (is (not (fs/existsSync bd-skill))
                  "Before the start, the backseat-driver skill isn't in the skills home")
            {:keys [socket]} (await (mcp/start-mcp-session!))]
        (is (fs/existsSync (path/join bd-skill "SKILL.md"))
            "The start put the backseat-driver skill in the skills home")
        (is (re-find #"(?m)^name: backseat-driver$" (fs/readFileSync (path/join bd-skill "SKILL.md") "utf8"))
            "It is the backseat-driver skill")
        (is (fs/existsSync (path/join bd-skill "references" "output-log-queries.md"))
            "The skill has its reference file")
        (is (fs/existsSync (path/join edit-skill "SKILL.md"))
            "The editing-clojure-files skill is in the skills home")
        (await (mcp/stop-mcp-session! socket)))
      (catch :default e
        (js/console.error "[skills-home] Error:" (.-message e) e)
        (await (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer"))
        (throw e)))))

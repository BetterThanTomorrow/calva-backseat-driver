(ns tests.mcp.eca-config-test
  "Consumer Extension Host proof that ECA upsert pretty-prints owned mcpServers
  entries, and that the entry is the same on every machine: `node -e` requires the
  wrapper under `os.homedir()`, with no port file while the window registry is on
  (Backseat Driver #67 / #70 / #74)."
  (:require
   ["fs" :as fs]
   ["os" :as os]
   ["path" :as path]
   ["vscode" :as vscode]
   [cljs.test :refer [is testing]]
   [clojure.string :as str]
   [e2e.macros :refer [deftest-async]]
   [e2e.mcp-helpers :as mcp]
   [e2e.utils :refer [wait-for+]]))

(def ^:private eca-extension-id "editor-code-assistant.eca")
(def ^:private server-key "calva-backseat-driver")

(def ^:private seed-config
  (str "{\n"
       "    \"$schema\": \"https://eca.dev/config.json\"\n"
       "    // e2e seed — preserve indent\n"
       "}\n"))

(defn- config-path []
  (path/join (.-fsPath mcp/workspace-uri) ".eca" "config.json"))

(defn- config-uri []
  (vscode/Uri.joinPath mcp/workspace-uri ".eca" "config.json"))

(defn- ^:async write-seed!+ []
  (let [data (.encode (js/TextEncoder.) seed-config)
        dir (vscode/Uri.joinPath mcp/workspace-uri ".eca")]
    (try
      (await (vscode/workspace.fs.createDirectory dir))
      (catch :default _ nil))
    (await (vscode/workspace.fs.writeFile (config-uri) data))))

(defn- read-config-text []
  (let [p (config-path)]
    (when (fs/existsSync p)
      (fs/readFileSync p "utf8"))))

(defn- delete-config! []
  (let [p (config-path)]
    (when (fs/existsSync p)
      (fs/unlinkSync p))))

(defn- compact-owned-entry? [text]
  (str/includes? text (str "\"" server-key "\":{")))

(defn- owned-entry
  "Parses the config, minus its whole-line `//` comments, and returns the owned entry."
  [text]
  (-> (->> (str/split-lines text)
           (remove #(re-find #"^\s*//" %))
           (str/join "\n"))
      js/JSON.parse
      js->clj
      (get-in ["mcpServers" server-key])))

(def ^:private expected-script
  "require(require('path').join(require('os').homedir(),'.config','calva','backseat-driver','calva-mcp-server.js'))")

(deftest-async eca-upsert-pretty-prints-owned-entry
  (testing "startMcpServer upserts calva-backseat-driver into .eca/config.json with pretty indent and a machine-independent entry"
    (is (boolean (vscode/extensions.getExtension eca-extension-id))
        "ECA extension must be installed for this e2e (launch.js installs editor-code-assistant.eca)")
    (is (true? (.get (vscode/workspace.getConfiguration "calva-backseat-driver") "enableMcpRegistry"))
        "window registry is on, so the entry names no port file")
    (try
      (let [_ (await (write-seed!+))
            _ (await (wait-for+ #(.-isActive (vscode/extensions.getExtension "betterthantomorrow.calva-backseat-driver"))
                                :timeout 15000
                                :message "[eca-config] Extension not active within 15s"))
            _ (await (vscode/commands.executeCommand "calva-backseat-driver.startMcpServer"))
            text (await (wait-for+ (fn []
                                     (when-let [t (read-config-text)]
                                       (when (and (str/includes? t server-key)
                                                  (not (compact-owned-entry? t)))
                                         t)))
                                   :timeout 15000
                                   :message "[eca-config] Pretty ECA upsert not observed within 15s"))
            _ (await (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer"))]
        (is (str/includes? text "// e2e seed — preserve indent")
            "Preserves seed comment")
        (is (str/includes? text "\n    \"mcpServers\"")
            "mcpServers key matches 4-space indent of seed file")
        (is (str/includes? text "\n            \"command\": \"node\"")
            "owned entry command is pretty-printed under 4-space indent")
        (is (not (compact-owned-entry? text))
            "owned entry is not a compact one-line object")
        (let [{command "command" args "args" :as entry} (owned-entry text)
              entry-strings (cons command args)
              home (os/homedir)]
          (is (= "node" command)
              "command is node")
          (is (= ["-e" expected-script "calva-mcp-server.js"] args)
              "args are -e, the script requiring the wrapper under os.homedir(), and the wrapper's file name (BD #74)")
          (is (not-any? #(str/ends-with? % ".port") args)
              "no port file arg while the window registry is on")
          (is (not-any? #(str/includes? % "${env:") entry-strings)
              "no ${env:...} variable, which ECA can't expand where HOME is unset (BD #74)")
          (is (not-any? #(or (str/includes? % home)
                             (str/includes? % "/Users/"))
                        entry-strings)
              (str "no home directory path in the entry: " (pr-str entry)))))
      (catch :default e
        (js/console.error "[eca-config]" (.-message e) e)
        (await (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer"))
        (throw e))
      (finally
        (delete-config!)))))

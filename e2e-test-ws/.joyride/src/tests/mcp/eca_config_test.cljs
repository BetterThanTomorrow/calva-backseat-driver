(ns tests.mcp.eca-config-test
  "Consumer Extension Host proof that ECA upsert pretty-prints owned mcpServers
  entries (Backseat Driver #67 / advanced-compile jsonc formatting)."
  (:require
   ["fs" :as fs]
   ["path" :as path]
   ["vscode" :as vscode]
   [cljs.test :refer [is testing]]
   [clojure.string :as str]
   [e2e.macros :refer [deftest-async]]
   [e2e.mcp-helpers :as mcp]
   [e2e.utils :refer [wait-for+]]
   [promesa.core :as p]))

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

(defn- write-seed!+ []
  (let [data (.encode (js/TextEncoder.) seed-config)
        dir (vscode/Uri.joinPath mcp/workspace-uri ".eca")]
    (-> (vscode/workspace.fs.createDirectory dir)
        (p/catch (fn [_] nil))
        (p/then (fn [_] (vscode/workspace.fs.writeFile (config-uri) data))))))

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

(deftest-async eca-upsert-pretty-prints-owned-entry
  (testing "startMcpServer upserts calva-backseat-driver into .eca/config.json with pretty indent"
    (is (boolean (vscode/extensions.getExtension eca-extension-id))
        "ECA extension must be installed for this e2e (launch.js installs editor-code-assistant.eca)")
    (-> (p/let [_ (write-seed!+)
                _ (wait-for+ #(.-isActive (vscode/extensions.getExtension "betterthantomorrow.calva-backseat-driver"))
                             :timeout 15000
                             :message "[eca-config] Extension not active within 15s")
                _ (vscode/commands.executeCommand "calva-backseat-driver.startMcpServer")
                text (wait-for+ (fn []
                                  (when-let [t (read-config-text)]
                                    (when (and (str/includes? t server-key)
                                               (not (compact-owned-entry? t)))
                                      t)))
                                :timeout 15000
                                :message "[eca-config] Pretty ECA upsert not observed within 15s")
                _ (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")]
          (is (str/includes? text "// e2e seed — preserve indent")
              "Preserves seed comment")
          (is (str/includes? text "\n    \"mcpServers\"")
              "mcpServers key matches 4-space indent of seed file")
          (is (str/includes? text "\n            \"command\": \"node\"")
              "owned entry command is pretty-printed under 4-space indent")
          (is (not (compact-owned-entry? text))
              "owned entry is not a compact one-line object"))
        (p/catch (fn [e]
                   (js/console.error "[eca-config]" (.-message e) e)
                   (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                   (throw e)))
        (p/finally (fn [] (delete-config!))))))

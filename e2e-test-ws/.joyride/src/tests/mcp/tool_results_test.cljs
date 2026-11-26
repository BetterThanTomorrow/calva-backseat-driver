(ns tests.mcp.tool-results-test
  "Pins what each tool returns, case by case, through MCP and through the Language Model API."
  (:require
   ["fs" :as fs]
   ["path" :as path]
   ["vscode" :as vscode]
   [cljs.test :refer [is testing]]
   [clojure.string :as string]
   [e2e.macros :refer [deftest-async]]
   [e2e.mcp-helpers :as mcp]
   [e2e.utils :refer [wait-for+]]
   [promesa.core :as p]))

(def ^:private who "e2e-tool-results")

(def ^:private !request-id (atom 5000))

(defn- mcp-call+
  "Calls `tool` through MCP. Resolves to `{:data … :is-error? … :rpc-error …}`."
  [socket tool arguments]
  (p/let [response (mcp/send-request socket {:jsonrpc "2.0"
                                             :id (swap! !request-id inc)
                                             :method "tools/call"
                                             :params {:name tool
                                                      :arguments arguments}})
          {:keys [result error]} (js->clj response :keywordize-keys true)
          text (get-in result [:content 0 :text])]
    {:data (when text
             (js->clj (js/JSON.parse text) :keywordize-keys true))
     :is-error? (:isError result)
     :rpc-error error}))

(defn- lm-call+
  "Calls `tool` through the Language Model API. Resolves to `{:data …}`, or to
   `{:rejected message}` when the call rejects."
  [tool arguments]
  (-> (p/let [^js result (vscode/lm.invokeTool tool #js {:input (clj->js arguments)
                                                          :toolInvocationToken js/undefined})
              text (some-> result .-content (aget 0) .-value)]
        {:data (when text
                 (js->clj (js/JSON.parse text) :keywordize-keys true))})
      (p/catch (fn [e]
                 {:rejected (.-message e)}))))

(defn- both+
  "Calls `tool` through MCP and then through the Language Model API."
  [socket tool arguments]
  (p/let [through-mcp (mcp-call+ socket tool arguments)
          through-lm (lm-call+ tool arguments)]
    {:mcp through-mcp :lm through-lm}))

(defn- session-key+ [socket]
  (p/let [{:keys [data]} (mcp-call+ socket "clojure_list_sessions" {})]
    (or (some (fn [session]
                (when (:currentRoutedTarget session)
                  (:replSessionKey session)))
              (:sessions data))
        (:replSessionKey (first (:sessions data))))))

(defn- is-same-data
  "Asserts that MCP and the Language Model API gave `expected`."
  [expected {through-mcp :mcp through-lm :lm} case-name]
  (is (= expected (:data through-mcp))
      (str case-name ", through MCP"))
  (is (= expected (:data through-lm))
      (str case-name ", through the Language Model API: " (pr-str through-lm))))

;; --- The cases ---

(defn- test-language-model-tools-registered []
  (testing "the Language Model API has the tools"
    (let [tool-names (set (map #(.-name %) (.-tools vscode/lm)))]
      (is (contains? tool-names "clojure_balance_brackets")
          (str "clojure_balance_brackets is registered: " (pr-str (filter #(string/starts-with? % "cloj") tool-names)))))))

(defn- test-balance-brackets+ [socket]
  (p/let [unbalanced (both+ socket "clojure_balance_brackets" {:text "(def foo [a b"})
          balanced (both+ socket "clojure_balance_brackets" {:text "(def foo [a b])"})]
    (testing "clojure_balance_brackets"
      (is-same-data {:balanced-text "(def foo [a b])"
                     :note "This is the complete, bracket-balanced version of the code. REPLACE THE ENTIRE previous text (the input to the tool) with this output. The changes/fixes require NO analysis or comments from you, WHATSOEVER."}
                    unbalanced "unbalanced text")
      (is-same-data {:note "The text was already properly balanced."}
                    balanced "balanced text"))))

(defn- test-evaluation-input-errors+ [socket session-key]
  (p/let [blank-who (both+ socket "clojure_evaluate_code"
                           {:code "(+ 1 2)" :namespace "user" :replSessionKey session-key :who ""})
          reserved-who (both+ socket "clojure_evaluate_code"
                              {:code "(+ 1 2)" :namespace "user" :replSessionKey session-key :who "ui"})
          unbalanced (both+ socket "clojure_evaluate_code"
                            {:code "(+ 1 2" :namespace "user" :replSessionKey session-key :who who})
          unknown-session (both+ socket "clojure_evaluate_code"
                                 {:code "(+ 1 2)" :namespace "user" :replSessionKey "no-such-session" :who who})]
    (testing "clojure_evaluate_code, input errors"
      (is-same-data {:error "The `who` parameter is required. Provide a slug identifying your agent (e.g. \"copilot\")."}
                    blank-who "blank who")
      (is-same-data {:error "The `who` value \"ui\" is reserved by Calva. Choose a different identifier."}
                    reserved-who "reserved who")
      (is-same-data {:valid? false
                     :error "Code has unbalanced brackets. The bracket balancer would change the code structure, which could alter its meaning. See the 'balanced-code' field for what the balancer would produce. If that looks correct, please re-run this tool using that code. Otherwise, fix the indentation and bracket balance before re-running"
                     :balanced-code "(+ 1 2)"}
                    unbalanced "unbalanced code")
      (is (= {:valid? false
              :error "Session 'no-such-session' not found."}
             (select-keys (get-in unknown-session [:mcp :data]) [:valid? :error]))
          "unknown session, through MCP")
      (is (seq (get-in unknown-session [:mcp :data :available-sessions]))
          "unknown session lists the available sessions")
      (is (= (get-in unknown-session [:mcp :data])
             (get-in unknown-session [:lm :data]))
          "unknown session, the same through the Language Model API"))))

(defn- test-evaluation-results+ [socket session-key]
  (p/let [arguments {:code "(+ 1 2)" :namespace "user" :replSessionKey session-key :who who}
          _warm-up (mcp-call+ socket "clojure_evaluate_code" arguments)
          success (both+ socket "clojure_evaluate_code" arguments)
          no-namespace (mcp-call+ socket "clojure_evaluate_code" (dissoc arguments :namespace))
          thrown (both+ socket "clojure_evaluate_code"
                        (assoc arguments :code "(throw (js/Error. \"e2e-tool-results boom\"))"))
          stable-keys [:result :ns :session-key :notes :who :error]]
    (testing "clojure_evaluate_code, results"
      (is (= {:result "3" :ns "user" :session-key session-key :notes [] :who who}
             (select-keys (get-in success [:mcp :data]) stable-keys))
          "a success, through MCP")
      (is (= (select-keys (get-in success [:mcp :data]) stable-keys)
             (select-keys (get-in success [:lm :data]) stable-keys))
          "a success, the same through the Language Model API")
      (is (= ["When evaluating without providing a namespace argument the evaluation is performed in the `user` namespace. Most often this is not what you want, and instead you should be evaluating providing the namespace argument. If it is the first time you are using a namespace, evaluate its ns-form first."]
             (:notes (:data no-namespace)))
          "no namespace gives the note about the user namespace")
      (is (= (select-keys (get-in thrown [:mcp :data]) stable-keys)
             (select-keys (get-in thrown [:lm :data]) stable-keys))
          (str "a thrown evaluation, the same through both: " (pr-str (get-in thrown [:mcp :data])))))))

(defn- test-list-sessions+ [socket]
  (p/let [{through-mcp :mcp through-lm :lm} (both+ socket "clojure_list_sessions" {})
          stable (fn [{:keys [data]}]
                   (mapv #(select-keys % [:replSessionKey :replType :projectRoot :globs :supportsRuntimes])
                         (:sessions data)))]
    (testing "clojure_list_sessions"
      (is (seq (stable through-mcp))
          "lists a session through MCP")
      (is (= (stable through-mcp) (stable through-lm))
          "the same sessions through the Language Model API"))))

(defn- test-symbol-info+ [socket session-key]
  (p/let [{through-mcp :mcp through-lm :lm} (both+ socket "clojure_symbol_info"
                                                   {:clojureSymbol "map"
                                                    :namespace "user"
                                                    :replSessionKey session-key})]
    (testing "clojure_symbol_info"
      (is (some? (:data through-mcp))
          "gives symbol info through MCP")
      (is (= (:data through-mcp) (:data through-lm))
          "the same through the Language Model API"))))

(defn- test-output-log+ [socket]
  (p/let [query "[:find [?text ...] :in $ ?who :where [?e :output/who ?who] [?e :output/category \"evaluatedCode\"] [?e :output/text ?text]]"
          found (both+ socket "clojure_repl_output_log" {:query query :inputs [who]})
          failing (both+ socket "clojure_repl_output_log"
                         {:query "[:find ?e :where [?e :output/text ?text] [(clojure.string/includes? ?nope \"x\")]]"})]
    (testing "clojure_repl_output_log"
      (is (some #{"(+ 1 2)"} (get-in found [:mcp :data]))
          "finds this test's evaluated code through MCP")
      (is (= (set (get-in found [:mcp :data]))
             (set (get-in found [:lm :data])))
          "the same through the Language Model API")
      (is (= -32603 (get-in failing [:mcp :rpc-error :code]))
          "a query that fails is a JSON-RPC error through MCP")
      (is (re-find #"Insufficient bindings" (str (get-in failing [:mcp :rpc-error :message])))
          "with Datascript's message")
      (is (re-find #"Insufficient bindings" (str (get-in failing [:lm :rejected])))
          (str "and a rejection with the same message through the Language Model API: " (pr-str (:lm failing)))))))

(defn- test-load-file+ [socket session-key]
  (p/let [missing-file (both+ socket "clojure_load_file"
                              {:filePath "/nonexistent/path/foo.clj" :replSessionKey session-key :who who})
          blank-who (both+ socket "clojure_load_file"
                           {:filePath "/nonexistent/path/foo.clj" :replSessionKey session-key :who ""})
          blank-session (both+ socket "clojure_load_file"
                               {:filePath "/nonexistent/path/foo.clj" :replSessionKey "" :who who})]
    (testing "clojure_load_file"
      (is (true? (get-in missing-file [:mcp :is-error?]))
          "a failed load has isError through MCP")
      (is (string? (get-in missing-file [:mcp :data :error]))
          "a failed load gives the error text")
      (is (= (get-in missing-file [:mcp :data])
             (get-in missing-file [:lm :data]))
          "a failed load, the same data through the Language Model API")
      (is-same-data {:error "The `who` parameter is required. Provide a slug identifying your agent (e.g. \"copilot\")."}
                    blank-who "blank who")
      (is-same-data {:error "The `replSessionKey` parameter is required. Use `clojure_list_sessions` to discover available sessions."}
                    blank-session "a blank session key"))))

(defn- test-edit-files+ [socket]
  (let [file-path (path/join (.-fsPath mcp/workspace-uri) "tool_results_edit_target.clj")
        file-key (keyword file-path)
        content "(ns tool-results-edit-target)\n\n(defn add-numbers\n  [a b]\n  (+ a b))\n"
        without-diagnostics (fn [data]
                              (update-in data [:files file-key]
                                         dissoc :diagnostics-before-edit :diagnostics-after-edit))]
    (-> (p/let [_ (fs/writeFileSync file-path content)
                not-found (both+ socket "clojure_edit_files"
                                 {:edits [{:type "replace"
                                           :filePath file-path
                                           :line 3
                                           :targetLineText "(defn no-such-function"
                                           :newForm "(defn no-such-function [])"}]})
                relative-path (both+ socket "clojure_edit_files"
                                     {:edits [{:type "append"
                                               :filePath "relative/path.clj"
                                               :code "(def x 1)"}]})
                edit-result (get-in not-found [:mcp :data :files file-key :edits 0])]
          (testing "clojure_edit_files"
            (is (= "0/1 edits applied across 1 file" (get-in not-found [:mcp :data :summary]))
                "a target that isn't found applies nothing")
            (is (= {:success false
                    :error "Target line text not found. Expected: '(defn no-such-function' near line 3"
                    :remedy "The target text was not found in the context shown below. Read the file to understand the actual structure, then target the correct line with the first line of the existing top level form starting at that line."
                    :file-context " 1 | (ns tool-results-edit-target)\n 2 | \n→3 | (defn add-numbers\n 4 |   [a b]\n 5 |   (+ a b))\n 6 | "
                    :index 0}
                   edit-result)
                "with the error, the remedy and the file context")
            (is (= #{:file-path :edits :disk-matches-buffer? :diagnostics-before-edit :diagnostics-after-edit}
                   (set (keys (get-in not-found [:mcp :data :files file-key]))))
                "the file's result has its keys")
            (is (= (without-diagnostics (get-in not-found [:mcp :data]))
                   (without-diagnostics (get-in not-found [:lm :data])))
                "the same through the Language Model API")
            (is (= content (fs/readFileSync file-path "utf8"))
                "and the file is untouched")
            (is-same-data {:error "Schema validation failed — no edits were applied"
                           :validation-errors [{:index 0
                                                :error "filePath must be an absolute path (starting with /)"}]}
                          relative-path "a relative path")))
        (p/finally (fn []
                     (when (fs/existsSync file-path)
                       (fs/unlinkSync file-path)))))))

(defn- test-resource-templates+ [socket session-key]
  (p/let [templates-response (mcp/send-request socket {:jsonrpc "2.0"
                                                       :id (swap! !request-id inc)
                                                       :method "resources/templates/list"})
          templates (-> templates-response (js->clj :keywordize-keys true) :result :resourceTemplates)
          uri (str "/symbol-info/map@" session-key "@user")
          read-response (mcp/send-request socket {:jsonrpc "2.0"
                                                  :id (swap! !request-id inc)
                                                  :method "resources/read"
                                                  :params {:uri uri}})
          contents (-> read-response (js->clj :keywordize-keys true) :result :contents)]
    (testing "resources/templates/list"
      (is (= [{:uriTemplate "/symbol-info/{symbol}@{session-key}@{namespace}"
               :name "symbol-info"
               :mimeType "application/json"}
              {:uriTemplate "/clojuredocs/{symbol}"
               :name "clojuredocs"
               :mimeType "application/json"}]
             (mapv #(select-keys % [:uriTemplate :name :mimeType]) templates))
          "lists the two templates")
      (is (every? (comp string? :description) templates)
          "each with its tool's description"))
    (testing "resources/read of a symbol-info resource"
      (is (= [uri] (mapv :uri contents))
          "answers with the uri")
      (is (string? (:text (first contents)))
          "and the info as text"))))

;; --- Test orchestrator ---

(deftest-async tool-results
  (let [backup-path (mcp/backup-settings! "tool-results-test-backup.json")]
    (-> (p/let [_ (mcp/ensure-repl-and-eval-enabled!)
                {:keys [socket]} (mcp/start-mcp-session!)
                _ (mcp/wait-for-tool! socket "clojure_evaluate_code")
                session-key (session-key+ socket)]
          (test-language-model-tools-registered)
          (p/do (test-balance-brackets+ socket)
                (test-evaluation-input-errors+ socket session-key)
                (test-evaluation-results+ socket session-key)
                (test-list-sessions+ socket)
                (test-symbol-info+ socket session-key)
                (test-output-log+ socket)
                (test-load-file+ socket session-key)
                (test-edit-files+ socket)
                (test-resource-templates+ socket session-key)
                (mcp/stop-mcp-session! socket)))
        (p/catch (fn [e]
                   (js/console.error "[tool-results] Error:" (.-message e) e)
                   (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                   (throw e)))
        (p/finally (fn []
                     (mcp/restore-settings! backup-path))))))

(deftest-async a-tool-whose-setting-is-off
  (let [backup-path (mcp/backup-settings! "tool-results-setting-off-backup.json")]
    (-> (p/let [_ (mcp/ensure-repl-and-eval-enabled!)
                {:keys [socket]} (mcp/start-mcp-session!)
                session-key (session-key+ socket)
                config (vscode/workspace.getConfiguration "calva-backseat-driver")
                _ (.update config "enableMcpReplEvaluation" false vscode/ConfigurationTarget.Workspace)
                _ (wait-for+ #(false? (.get (vscode/workspace.getConfiguration "calva-backseat-driver")
                                            "enableMcpReplEvaluation"))
                             :timeout 15000
                             :message "[tool-results] enableMcpReplEvaluation false not propagated within 15s")
                arguments {:code "(+ 1 2)" :namespace "user" :replSessionKey session-key :who who}
                through-mcp (mcp-call+ socket "clojure_evaluate_code" arguments)
                through-lm (lm-call+ "clojure_evaluate_code" arguments)
                _ (mcp/stop-mcp-session! socket)]
          (testing "clojure_evaluate_code with enableMcpReplEvaluation off"
            (is (= {:code -32601 :message "Unknown tool"}
                   (:rpc-error through-mcp))
                "is an unknown tool through MCP")
            (is (string? (:rejected through-lm))
                (str "and is refused through the Language Model API: " (pr-str through-lm)))))
        (p/catch (fn [e]
                   (js/console.error "[tool-results] Error:" (.-message e) e)
                   (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                   (throw e)))
        (p/finally (fn []
                     (mcp/restore-settings! backup-path))))))

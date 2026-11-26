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
   [e2e.utils :refer [wait-for+]]))

(def ^:private who "e2e-tool-results")

(def ^:private !request-id (atom 5000))

(defn- ^:async mcp-call+
  "Calls `tool` through MCP. Resolves to `{:data … :is-error? … :rpc-error …}`."
  [socket tool arguments]
  (let [response (await (mcp/send-request socket {:jsonrpc "2.0"
                                                  :id (swap! !request-id inc)
                                                  :method "tools/call"
                                                  :params {:name tool
                                                           :arguments arguments}}))
        {:keys [result error]} (js->clj response :keywordize-keys true)
        text (get-in result [:content 0 :text])]
    {:data (when text
             (js->clj (js/JSON.parse text) :keywordize-keys true))
     :is-error? (:isError result)
     :rpc-error error}))

(defn- ^:async lm-call+
  "Calls `tool` through the Language Model API. Resolves to `{:data …}`, or to
   `{:rejected message}` when the call rejects."
  [tool arguments]
  (try
    (let [^js result (await (vscode/lm.invokeTool tool #js {:input (clj->js arguments)
                                                             :toolInvocationToken js/undefined}))
          text (some-> result .-content (aget 0) .-value)]
      {:data (when text
               (js->clj (js/JSON.parse text) :keywordize-keys true))})
    (catch :default e
      {:rejected (.-message e)})))

(defn- ^:async mcp-content+
  "Calls `tool` through MCP. Resolves to the result's content parts, as they came."
  [socket tool arguments]
  (let [response (await (mcp/send-request socket {:jsonrpc "2.0"
                                                  :id (swap! !request-id inc)
                                                  :method "tools/call"
                                                  :params {:name tool
                                                           :arguments arguments}}))]
    (-> response (js->clj :keywordize-keys true) :result :content)))

(defn- ^:async lm-parts+
  "Calls `tool` through the Language Model API. Resolves to a map per result part:
   `:value` for a text part, `:mime-type` and `:data?` for a data part."
  [tool arguments]
  (let [^js result (await (vscode/lm.invokeTool tool #js {:input (clj->js arguments)
                                                           :toolInvocationToken js/undefined}))]
    (mapv (fn [^js part]
            {:value (.-value part)
             :mime-type (.-mimeType part)
             :data? (some? (.-data part))})
          (.-content result))))

(defn- ^:async both+
  "Calls `tool` through MCP and then through the Language Model API."
  [socket tool arguments]
  (let [through-mcp (await (mcp-call+ socket tool arguments))
        through-lm (await (lm-call+ tool arguments))]
    {:mcp through-mcp :lm through-lm}))

(defn- ^:async session-key+ [socket]
  (let [{:keys [data]} (await (mcp-call+ socket "clojure_list_sessions" {}))]
    (or (some (fn [session]
                (when (:currentRoutedTarget session)
                  (:replSessionKey session)))
              (:sessions data))
        (:replSessionKey (first (:sessions data))))))

(defn- data-without
  "Drops `k` from the data of both answers."
  [both k]
  (-> both
      (update-in [:mcp :data] dissoc k)
      (update-in [:lm :data] dissoc k)))

(defn- language-model-tools?
  "True when the editor lists Language Model tools. Cursor lists none, and rejects
   every call through the Language Model API."
  []
  (pos? (count (.-tools vscode/lm))))

(defn- skipped [what]
  (js/console.log "[tool-results] This editor has no Language Model tools:" what "skipped"))

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

(defn- ^:async test-balance-brackets+ [socket]
  (let [unbalanced (await (both+ socket "clojure_balance_brackets" {:text "(def foo [a b"}))
        balanced (await (both+ socket "clojure_balance_brackets" {:text "(def foo [a b])"}))]
    (testing "clojure_balance_brackets"
      (is-same-data {:balanced-text "(def foo [a b])"
                     :note "This is the complete, bracket-balanced version of the code. REPLACE THE ENTIRE previous text (the input to the tool) with this output. The changes/fixes require NO analysis or comments from you, WHATSOEVER."}
                    unbalanced "unbalanced text")
      (is-same-data {:note "The text was already properly balanced."}
                    balanced "balanced text"))))

(defn- ^:async test-evaluation-input-errors+ [socket session-key]
  (let [blank-who (await (both+ socket "clojure_evaluate_code"
                                {:code "(+ 1 2)" :namespace "user" :replSessionKey session-key :who ""}))
        reserved-who (await (both+ socket "clojure_evaluate_code"
                                   {:code "(+ 1 2)" :namespace "user" :replSessionKey session-key :who "ui"}))
        unbalanced (await (both+ socket "clojure_evaluate_code"
                                 {:code "(+ 1 2" :namespace "user" :replSessionKey session-key :who who}))
        unknown-session (await (both+ socket "clojure_evaluate_code"
                                      {:code "(+ 1 2)" :namespace "user" :replSessionKey "no-such-session" :who who}))]
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

(defn- ^:async test-evaluation-results+ [socket session-key]
  (let [arguments {:code "(+ 1 2)" :namespace "user" :replSessionKey session-key :who who}
        _warm-up (await (mcp-call+ socket "clojure_evaluate_code" arguments))
        success (await (both+ socket "clojure_evaluate_code" arguments))
        printing (await (both+ socket "clojure_evaluate_code"
                               (assoc arguments :code "(do (println \"e2e-tool-results out\") (+ 1 2))")))
        no-namespace (await (mcp-call+ socket "clojure_evaluate_code" (dissoc arguments :namespace)))
        thrown (await (both+ socket "clojure_evaluate_code"
                             (assoc arguments :code "(throw (js/Error. \"e2e-tool-results boom\"))")))
        result {:result "3" :ns "user" :stdout nil :stderr nil :session-key session-key :notes [] :who who}]
    (testing "clojure_evaluate_code, results"
      (is-same-data result success "a success")
      (is-same-data (assoc result :stdout "e2e-tool-results out\n")
                    printing "a success that prints")
      (is (= ["When evaluating without providing a namespace argument the evaluation is performed in the `user` namespace. Most often this is not what you want, and instead you should be evaluating providing the namespace argument. If it is the first time you are using a namespace, evaluate its ns-form first."]
             (:notes (:data no-namespace)))
          "no namespace gives the note about the user namespace")
      (is-same-data {:result "nil"
                     :ns "user"
                     :stdout nil
                     :stderr "e2e-tool-results boom\n"
                     :stacktrace nil
                     :session-key session-key
                     :notes []
                     :who who}
                    (data-without thrown :error) "a thrown evaluation, without its error")
      (is (string/starts-with? (str (get-in thrown [:mcp :data :error]))
                               "#error {:message \"e2e-tool-results boom\"")
          (str "a thrown evaluation has the error through MCP: " (pr-str (get-in thrown [:mcp :data :error]))))
      (is (string/starts-with? (str (get-in thrown [:lm :data :error]))
                               "#error {:message \"e2e-tool-results boom\"")
          "and through the Language Model API"))))

(defn- ^:async test-list-sessions+ [socket]
  (let [{through-mcp :mcp through-lm :lm} (await (both+ socket "clojure_list_sessions" {}))
        joyride-session (fn [{:keys [data]}]
                          (some (fn [session]
                                  (when (= "joyride" (:replSessionKey session))
                                    session))
                                (:sessions data)))
        changing-keys [:lastActivity :currentRoutedTarget :globs]
        stable (fn [answer]
                 (apply dissoc (joyride-session answer) changing-keys))]
    (testing "clojure_list_sessions"
      (is (= {:replSessionKey "joyride"
              :replType "clj"
              :projectRoot (.-fsPath mcp/workspace-uri)
              :supportsRuntimes false
              :hasBuilds false
              :availableBuilds nil
              :currentlyConnectedCljsBuild nil
              :currentlyConnectedRuntimeId nil}
             (stable through-mcp))
          "lists the Joyride session through MCP")
      (is (every? (set (keys (joyride-session through-mcp))) changing-keys)
          "with its last activity, routing and globs")
      (is (some #{"**/*.cljs"} (:globs (joyride-session through-mcp)))
          "the globs cover ClojureScript files")
      (is (= (dissoc (joyride-session through-mcp) :lastActivity)
             (dissoc (joyride-session through-lm) :lastActivity))
          "the same session through the Language Model API"))))

(defn- ^:async test-symbol-info+ [socket session-key]
  (let [answers (await (both+ socket "clojure_symbol_info"
                              {:clojureSymbol "map"
                               :namespace "user"
                               :replSessionKey session-key}))]
    (testing "clojure_symbol_info"
      (is-same-data {:error "Can't retrieve REPL session for session key. Is the REPL connected?"}
                    answers "the Joyride session, which Calva has no symbol info for")
      (is (nil? (get-in answers [:mcp :is-error?]))
          "and it is not marked as an error through MCP"))))

(defn- ^:async test-output-log+ [socket]
  (let [query "[:find [?text ...] :in $ ?who :where [?e :output/who ?who] [?e :output/category \"evaluatedCode\"] [?e :output/text ?text]]"
        found (await (both+ socket "clojure_repl_output_log" {:query query :inputs [who]}))
        map-input (await (both+ socket "clojure_repl_output_log"
                                {:query "[:find ?v . :in $ ?m :where [(get ?m :k) ?v]]"
                                 :inputs [{:k "from-a-keyword-key"}]}))
        failing (await (both+ socket "clojure_repl_output_log"
                              {:query "[:find ?e :where [?e :output/text ?text] [(clojure.string/includes? ?nope \"x\")]]"}))]
    (testing "clojure_repl_output_log"
      (is (some #{"(+ 1 2)"} (get-in found [:mcp :data]))
          "finds this test's evaluated code through MCP")
      (is (= (set (get-in found [:mcp :data]))
             (set (get-in found [:lm :data])))
          "the same through the Language Model API")
      (is-same-data "from-a-keyword-key"
                    map-input "a map in the inputs is read with keyword keys")
      (is (= -32603 (get-in failing [:mcp :rpc-error :code]))
          "a query that fails is a JSON-RPC error through MCP")
      (is (re-find #"Insufficient bindings" (str (get-in failing [:mcp :rpc-error :message])))
          "with Datascript's message")
      (is (re-find #"Insufficient bindings" (str (get-in failing [:lm :rejected])))
          (str "and a rejection with the same message through the Language Model API: " (pr-str (:lm failing)))))))

(defn- ^:async test-load-file+ [socket session-key]
  (let [missing-file (await (both+ socket "clojure_load_file"
                                   {:filePath "/nonexistent/path/foo.clj" :replSessionKey session-key :who who}))
        blank-who (await (both+ socket "clojure_load_file"
                                {:filePath "/nonexistent/path/foo.clj" :replSessionKey session-key :who ""}))
        blank-session (await (both+ socket "clojure_load_file"
                                    {:filePath "/nonexistent/path/foo.clj" :replSessionKey "" :who who}))]
    (testing "clojure_load_file"
      (is (true? (get-in missing-file [:mcp :is-error?]))
          "a failed load has isError through MCP")
      (is (= [:error] (keys (get-in missing-file [:mcp :data])))
          "a failed load gives only the error")
      (is (string/starts-with? (str (get-in missing-file [:mcp :data :error]))
                               "cannot open file:///nonexistent/path/foo.clj. Detail: ")
          (str "with the text VS Code gave: " (pr-str (get-in missing-file [:mcp :data :error]))))
      (is (= (get-in missing-file [:mcp :data])
             (get-in missing-file [:lm :data]))
          "a failed load, the same data through the Language Model API")
      (is-same-data {:error "The `who` parameter is required. Provide a slug identifying your agent (e.g. \"copilot\")."}
                    blank-who "blank who")
      (is-same-data {:error "The `replSessionKey` parameter is required. Use `clojure_list_sessions` to discover available sessions."}
                    blank-session "a blank session key"))))

(defn- ^:async test-edit-files+ [socket]
  (let [file-path (path/join (.-fsPath mcp/workspace-uri) "tool_results_edit_target.clj")
        file-key (keyword file-path)
        content "(ns tool-results-edit-target)\n\n(defn add-numbers\n  [a b]\n  (+ a b))\n"
        without-diagnostics (fn [data]
                              (update-in data [:files file-key]
                                         dissoc :diagnostics-before-edit :diagnostics-after-edit))]
    (try
      (let [_ (fs/writeFileSync file-path content)
            not-found (await (both+ socket "clojure_edit_files"
                                    {:edits [{:type "replace"
                                              :filePath file-path
                                              :line 3
                                              :targetLineText "(defn no-such-function"
                                              :newForm "(defn no-such-function [])"}]}))
            relative-path (await (both+ socket "clojure_edit_files"
                                        {:edits [{:type "append"
                                                  :filePath "relative/path.clj"
                                                  :code "(def x 1)"}]}))
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
      (finally
        (when (fs/existsSync file-path)
          (fs/unlinkSync file-path))))))

(defn- ^:async set-context-padding+
  "Sets `editor.lineContextResponsePadding` in the workspace to `value`, and waits
   until the setting reads `expected`."
  [value expected]
  (let [setting "editor.lineContextResponsePadding"
        config #(vscode/workspace.getConfiguration "calva-backseat-driver")]
    (await (.update (config) setting value vscode/ConfigurationTarget.Workspace))
    (await (wait-for+ #(= expected (.get (config) setting))
                      :timeout 15000
                      :message (str "[tool-results] " setting " did not become " expected " within 15s")))))

(defn- ^:async test-context-padding+ [socket]
  (let [file-path (path/join (.-fsPath mcp/workspace-uri) "tool_results_padding_target.clj")
        default-padding (.get (vscode/workspace.getConfiguration "calva-backseat-driver")
                              "editor.lineContextResponsePadding")]
    (let [outcome (try
                    (let [_ (fs/writeFileSync file-path "(ns tool-results-padding-target)\n\n(defn add-numbers\n  [a b]\n  (+ a b))\n")
                          _ (await (set-context-padding+ 1 1))]
                      (await (mcp-call+ socket "clojure_edit_files"
                                        {:edits [{:type "replace"
                                                  :filePath file-path
                                                  :line 3
                                                  :targetLineText "(defn no-such-function"
                                                  :newForm "(defn no-such-function [])"}]})))
                    (catch :default e
                      {:failed e}))
          _ (await (set-context-padding+ js/undefined default-padding))]
      (when (fs/existsSync file-path)
        (fs/unlinkSync file-path))
      (when-let [e (:failed outcome)]
        (throw e))
      (testing "clojure_edit_files with lineContextResponsePadding 1"
        (is (= " 2 | \n→3 | (defn add-numbers\n 4 |   [a b]"
               (get-in outcome [:data :files (keyword file-path) :edits 0 :file-context]))
            "the file context is one line each side of the target")))))

(defn- ^:async test-clojuredocs-without-symbol+ [socket]
  (let [through-mcp (await (mcp-content+ socket "clojuredocs_info" {}))
        through-lm (await (lm-parts+ "clojuredocs_info" {}))]
    (testing "clojuredocs_info without a symbol, where Calva answers undefined"
      (is (= [{:type "text"}] through-mcp)
          "is a text part without text through MCP")
      (is (= ["null"] (mapv :value through-lm))
          "and the text null through the Language Model API"))))

(defn- ^:async test-image-parts+ [session-key]
  (let [arguments {:code "\"data:image/png;base64,iVBORw0KGgo=\""
                   :namespace "user"
                   :replSessionKey session-key
                   :who who}
        with-image (await (lm-parts+ "clojure_evaluate_code" arguments))
        capped (await (lm-parts+ "clojure_evaluate_code" (assoc arguments :maxImages 0)))
        result-of (fn [parts]
                    (-> parts first :value js/JSON.parse (js->clj :keywordize-keys true) :result))]
    (testing "an image in a result, through the Language Model API"
      (is (= "\"<<image-1>>\"" (result-of with-image))
          "the text part has a marker where the image was")
      (is (= ["image/png"] (mapv :mime-type (rest with-image)))
          "and a second part has the image's mime type")
      (is (true? (:data? (second with-image)))
          "and its data")
      (is (= "\"<<image-1-capped>>\"" (result-of capped))
          "with maxImages 0 the marker says the image is capped")
      (is (= 1 (count capped))
          "and the text part is the only part"))))

(defn- ^:async test-resource-templates+ [socket session-key]
  (let [templates-response (await (mcp/send-request socket {:jsonrpc "2.0"
                                                            :id (swap! !request-id inc)
                                                            :method "resources/templates/list"}))
        templates (-> templates-response (js->clj :keywordize-keys true) :result :resourceTemplates)
        uri (str "/symbol-info/map@" session-key "@user")
        read-response (await (mcp/send-request socket {:jsonrpc "2.0"
                                                       :id (swap! !request-id inc)
                                                       :method "resources/read"
                                                       :params {:uri uri}}))
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
    (try
      (let [_ (await (mcp/ensure-repl-and-eval-enabled!))
            {:keys [socket]} (await (mcp/start-mcp-session!))
            _ (await (mcp/wait-for-tool! socket "clojure_evaluate_code"))
            session-key (await (session-key+ socket))]
        (if-not (language-model-tools?)
          (skipped "the cases through the Language Model API are")
          (do
            (test-language-model-tools-registered)
            (await (test-balance-brackets+ socket))
            (await (test-evaluation-input-errors+ socket session-key))
            (await (test-evaluation-results+ socket session-key))
            (await (test-image-parts+ session-key))
            (await (test-list-sessions+ socket))
            (await (test-symbol-info+ socket session-key))
            (await (test-clojuredocs-without-symbol+ socket))
            (await (test-output-log+ socket))
            (await (test-load-file+ socket session-key))
            (await (test-edit-files+ socket))))
        (await (test-context-padding+ socket))
        (await (test-resource-templates+ socket session-key))
        (await (mcp/stop-mcp-session! socket)))
      (catch :default e
        (js/console.error "[tool-results] Error:" (.-message e) e)
        (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
        (throw e))
      (finally
        (await (mcp/restore-settings! backup-path))))))

(deftest-async a-tool-whose-setting-is-off
  (if-not (language-model-tools?)
    (skipped "a-tool-whose-setting-is-off is")
    (let [backup-path (mcp/backup-settings! "tool-results-setting-off-backup.json")]
      (try
        (let [_ (await (mcp/ensure-repl-and-eval-enabled!))
              {:keys [socket]} (await (mcp/start-mcp-session!))
              session-key (await (session-key+ socket))
              config (vscode/workspace.getConfiguration "calva-backseat-driver")
              _ (await (.update config "enableMcpReplEvaluation" false vscode/ConfigurationTarget.Workspace))
              _ (await (wait-for+ #(false? (.get (vscode/workspace.getConfiguration "calva-backseat-driver")
                                                 "enableMcpReplEvaluation"))
                                  :timeout 15000
                                  :message "[tool-results] enableMcpReplEvaluation false not propagated within 15s"))
              arguments {:code "(+ 1 2)" :namespace "user" :replSessionKey session-key :who who}
              through-mcp (await (mcp-call+ socket "clojure_evaluate_code" arguments))
              through-lm (await (lm-call+ "clojure_evaluate_code" arguments))
              _ (await (mcp/stop-mcp-session! socket))]
          (testing "clojure_evaluate_code with enableMcpReplEvaluation off"
            (is (= {:code -32601 :message "Unknown tool"}
                   (:rpc-error through-mcp))
                "is an unknown tool through MCP")
            (is (= "Unknown tool" (:rejected through-lm))
                (str "and through the Language Model API: " (pr-str through-lm)))))
        (catch :default e
          (js/console.error "[tool-results] Error:" (.-message e) e)
          (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
          (throw e))
        (finally
          (await (mcp/restore-settings! backup-path)))))))

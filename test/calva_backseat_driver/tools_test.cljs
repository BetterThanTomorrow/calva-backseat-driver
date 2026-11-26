(ns calva-backseat-driver.tools-test
  (:require ["fs" :as fs]
            ["path" :as path]
            [cljs.test :refer [deftest is testing async]]
            [calva-backseat-driver.tools :as tools]))

(deftest prepared-invocation
  (testing "clojure_evaluate_code"
    (is (= {:invocationMessage "Evaluating code"
            :confirmationMessages {:title "Evaluate as **coder** in the **clj** REPL"
                                   :message "Evaluate?\n```clojure\n(in-ns user)\n\n(+ 1 2)\n```"}}
           (tools/prepared-invocation "clojure_evaluate_code"
                                             {:code "(+ 1 2)" :namespace "user" :replSessionKey "clj" :who "coder"})))
    (is (= "Evaluate as **unknown** in the **cljs** REPL (runtime 83)"
           (get-in (tools/prepared-invocation "clojure_evaluate_code"
                                                     {:code "1" :replSessionKey "cljs" :targetRuntimeId 83})
                   [:confirmationMessages :title]))
        "without a who, and with a runtime"))
  (testing "clojure_list_sessions"
    (is (= {:invocationMessage "Listing REPL sessions"
            :confirmationMessages {:title "List REPL Sessions"
                                   :message "List available REPL sessions"}}
           (tools/prepared-invocation "clojure_list_sessions" {}))))
  (testing "clojure_symbol_info"
    (is (= {:invocationMessage "Getting symbol info"
            :confirmationMessages {:title "Get Symbol Info"
                                   :message "Get info for Clojure symbol: **map**"}}
           (tools/prepared-invocation "clojure_symbol_info" {:clojureSymbol "map"}))))
  (testing "clojuredocs_info"
    (is (= {:invocationMessage "Looking up ClojureDocs"
            :confirmationMessages {:title "Get ClojureDocs Info"
                                   :message "Look up docs for Clojure symbol: **map**"}}
           (tools/prepared-invocation "clojuredocs_info" {:clojureSymbol "map"}))))
  (testing "clojure_repl_output_log"
    (is (= {:invocationMessage "Querying REPL output log"
            :confirmationMessages {:title "Query REPL Output Log"
                                   :message "Query REPL output log: [:find ?e :where [?e :output/line]]"}}
           (tools/prepared-invocation "clojure_repl_output_log"
                                             {:query "[:find ?e :where [?e :output/line]]"}))))
  (testing "clojure_balance_brackets"
    (is (= {:invocationMessage "Inferred brackets"
            :confirmationMessages {:title "Infer brackets"
                                   :message "Infer from indents for: (def a"}}
           (tools/prepared-invocation "clojure_balance_brackets" {:text "(def a"}))))
  (testing "clojure_load_file"
    (is (= {:invocationMessage "Loading file"
            :confirmationMessages {:title "Load file as **coder**"
                                   :message "Load file: /a/b.clj in **clj** REPL"}}
           (tools/prepared-invocation "clojure_load_file"
                                             {:filePath "/a/b.clj" :replSessionKey "clj" :who "coder"})))
    (is (= {:title "Load file as **unknown**"
            :message "Load file: /a/b.clj"}
           (:confirmationMessages (tools/prepared-invocation "clojure_load_file" {:filePath "/a/b.clj"})))
        "without a who and a session key"))
  (testing "clojure_edit_files"
    (is (= {:invocationMessage "Editing Clojure files"
            :confirmationMessages {:title "Edit Clojure Files"
                                   :message "1 edit across 1 file"}}
           (tools/prepared-invocation "clojure_edit_files" {:edits [{:filePath "/a.clj"}]})))
    (is (= "3 edits across 2 files"
           (get-in (tools/prepared-invocation "clojure_edit_files"
                                                     {:edits [{:filePath "/a.clj"} {:filePath "/a.clj"} {:filePath "/b.clj"}]})
                   [:confirmationMessages :message])))))

(def ^:private manifest
  (js->clj (js/JSON.parse (fs/readFileSync (path/resolve "package.json") "utf8"))
           :keywordize-keys true))

(deftest the-tool-table-and-the-manifest
  (is (= (set (map :name (get-in manifest [:contributes :languageModelTools])))
         (set (keys tools/tools)))
      "Every tool in package.json has its row, and there are no others")
  (is (every? (fn [{:tool/keys [function invocation-message confirmation]}]
                (and (var? function)
                     (string? invocation-message)
                     (var? confirmation)))
              (vals tools/tools))
      "Every row has its function, its invocation message and its confirmation")
  (let [settings (set (map (fn [property]
                             (keyword "vscode" (str "config." (subs (name property) (count "calva-backseat-driver.")))))
                           (keys (get-in manifest [:contributes :configuration :properties]))))]
    (is (every? (conj settings :calva/output-log) (mapcat :tool/reads (vals tools/tools)))
        "What a tool reads from the state is a setting in package.json, or the output log")))

(deftest log-lines
  (is (= ["[Server] Getting symbol info for:" "map"]
         (tools/log-line "clojure_symbol_info" {:clojureSymbol "map" :namespace "user"})))
  (is (= ["[Server] Listing REPL sessions"]
         (tools/log-line "clojure_list_sessions" {})))
  (is (= ["[Server] Loading file:" nil]
         (tools/log-line "clojure_load_file" {}))
      "An argument the call leaves out is nil in the line")
  (is (nil? (tools/log-line "clojure_edit_files" {:edits []}))
      "Editing writes no line")
  (is (every? (fn [[tool {:tool/keys [log-line]}]]
                (let [arguments (set (map keyword (keys (get-in (some #(when (= tool (:name %)) %)
                                                                      (get-in manifest [:contributes :languageModelTools]))
                                                                [:inputSchema :properties]))))]
                  (every? arguments (filter keyword? log-line))))
              tools/tools)
      "Every argument a log line names is an argument of its tool in package.json"))

(deftest sessions-result
  (let [session {:replSessionKey "cljs"
                 :replType "cljs"
                 :supportsRuntimes true
                 :builds [{:buildId ":app"
                           :isActive true
                           :isCurrentlyConnected true
                           :runtimes [{:runtimeId 83 :description "Node" :buildId ":app" :host "node" :lastActivity 1 :extra "x"}]}]}]
    (is (= {:result/data {:sessions [{:replSessionKey "cljs"
                                      :replType "cljs"
                                      :supportsRuntimes true
                                      :builds [{:buildId ":app"
                                                :isActive true
                                                :isCurrentlyConnected true
                                                :runtimeCount 1
                                                :mostRecentRuntime {:runtimeId 83 :description "Node" :buildId ":app" :host "node" :lastActivity 1}}]}]}}
           (tools/sessions-result {} [session]))
        "Each build has its newest runtime and a count")
    (is (= [{:runtimeId 83 :description "Node" :buildId ":app" :host "node" :lastActivity 1}]
           (get-in (tools/sessions-result {:includeAllRuntimes true} [session])
                   [:result/data :sessions 0 :builds 0 :runtimes]))
        "and every runtime when the call asks for them")))

(deftest loading-a-file
  (is (= "The `replSessionKey` parameter is required. Use `clojure_list_sessions` to discover available sessions."
         (tools/load-file-input-error {:filePath "/a.clj" :replSessionKey "" :who "coder"})
         (tools/load-file-input-error {:filePath "/a.clj" :who "coder"})))
  (is (= "The `who` parameter is required. Provide a slug identifying your agent (e.g. \"copilot\")."
         (tools/load-file-input-error {:filePath "/a.clj" :replSessionKey "clj" :who ""})))
  (is (nil? (tools/load-file-input-error {:filePath "/a.clj" :replSessionKey "clj" :who "coder"})))
  (is (= {:result/data {:result "#'user/x"}}
         (tools/load-file-result {:load/result "#'user/x"})))
  (is (= {:result/data {:result "nil"}}
         (tools/load-file-result {:load/result nil}))
      "A load without a result gives the text nil")
  (is (= {:result/data {:error "cannot open file:///nope.clj"}
          :result/error? true
          :result/log [[:debug "[Server] Load file failed:" "cannot open file:///nope.clj"]]}
         (tools/load-file-result {:load/error "cannot open file:///nope.clj"}))
      "A load that fails is a failed tool call, with a log line"))

(deftest calling-a-tool
  (async done
    (let [!asked (atom [])
          perform-effect! (fn [[effect-name :as fx]]
                            (swap! !asked conj fx)
                            (js/Promise.resolve (get {:calva/fx.load-file {:load/error "no such file"}
                                                      :calva/fx.symbol-info js/undefined
                                                      :calva/fx.edit-files {:summary "0/0 edits applied across 0 files"}}
                                                     effect-name)))]
      (-> (js/Promise.all
           #js [(tools/call-tool+ perform-effect! {:call/tool "clojure_load_file"
                                                   :call/arguments {:filePath "/nope.clj" :replSessionKey "clj" :who "coder"}})
                (tools/call-tool+ perform-effect! {:call/tool "clojure_symbol_info"
                                                   :call/arguments {:clojureSymbol "map" :replSessionKey "clj" :namespace "user"}})
                (tools/call-tool+ perform-effect! {:call/tool "clojure_edit_files"
                                                   :call/arguments {:edits []}
                                                   :call/state {:vscode/config.editor.fuzzyLineTargetingPadding 2
                                                                :vscode/config.editor.lineContextResponsePadding 10}})])
          (.then (fn [[failed-load no-info edited]]
                   (is (= {:result/content [{:type "text" :text "{\"error\":\"no such file\"}"}]
                           :result/error? true
                           :result/log [[:debug "[Server] Load file failed:" "no such file"]]}
                          failed-load)
                       "A failed load is content with the error, marked as failed, with its log line")
                   (is (= {:result/content [{:type "text"}]} no-info)
                       "A tool that Calva answers undefined gives a text part without text")
                   (is (= [[:calva/fx.load-file "/nope.clj" "clj" "coder"]
                           [:calva/fx.symbol-info "map" "clj" "user"]
                           [:calva/fx.edit-files [] {:editor/search-padding 2 :editor/context-padding 10}]]
                          @!asked)
                       "Each tool asks Calva with the call's arguments, and editing with the paddings from the settings")
                   (is (= "{\"summary\":\"0/0 edits applied across 0 files\"}"
                          (-> edited :result/content first :text)))))
          (.catch (fn [e]
                    (is (nil? e) "The calls resolve")))
          (.finally done)))))

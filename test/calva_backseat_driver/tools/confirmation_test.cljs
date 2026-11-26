(ns calva-backseat-driver.tools.confirmation-test
  (:require ["fs" :as fs]
            ["path" :as path]
            [cljs.test :refer [deftest is testing]]
            [calva-backseat-driver.tools.confirmation :as confirmation]))

(deftest prepared-invocation
  (testing "clojure_evaluate_code"
    (is (= {:invocationMessage "Evaluating code"
            :confirmationMessages {:title "Evaluate as **coder** in the **clj** REPL"
                                   :message "Evaluate?\n```clojure\n(in-ns user)\n\n(+ 1 2)\n```"}}
           (confirmation/prepared-invocation "clojure_evaluate_code"
                                             {:code "(+ 1 2)" :namespace "user" :replSessionKey "clj" :who "coder"})))
    (is (= "Evaluate as **unknown** in the **cljs** REPL (runtime 83)"
           (get-in (confirmation/prepared-invocation "clojure_evaluate_code"
                                                     {:code "1" :replSessionKey "cljs" :targetRuntimeId 83})
                   [:confirmationMessages :title]))
        "without a who, and with a runtime"))
  (testing "clojure_list_sessions"
    (is (= {:invocationMessage "Listing REPL sessions"
            :confirmationMessages {:title "List REPL Sessions"
                                   :message "List available REPL sessions"}}
           (confirmation/prepared-invocation "clojure_list_sessions" {}))))
  (testing "clojure_symbol_info"
    (is (= {:invocationMessage "Getting symbol info"
            :confirmationMessages {:title "Get Symbol Info"
                                   :message "Get info for Clojure symbol: **map**"}}
           (confirmation/prepared-invocation "clojure_symbol_info" {:clojureSymbol "map"}))))
  (testing "clojuredocs_info"
    (is (= {:invocationMessage "Looking up ClojureDocs"
            :confirmationMessages {:title "Get ClojureDocs Info"
                                   :message "Look up docs for Clojure symbol: **map**"}}
           (confirmation/prepared-invocation "clojuredocs_info" {:clojureSymbol "map"}))))
  (testing "clojure_repl_output_log"
    (is (= {:invocationMessage "Querying REPL output log"
            :confirmationMessages {:title "Query REPL Output Log"
                                   :message "Query REPL output log: [:find ?e :where [?e :output/line]]"}}
           (confirmation/prepared-invocation "clojure_repl_output_log"
                                             {:query "[:find ?e :where [?e :output/line]]"}))))
  (testing "clojure_balance_brackets"
    (is (= {:invocationMessage "Inferred brackets"
            :confirmationMessages {:title "Infer brackets"
                                   :message "Infer from indents for: (def a"}}
           (confirmation/prepared-invocation "clojure_balance_brackets" {:text "(def a"}))))
  (testing "clojure_load_file"
    (is (= {:invocationMessage "Loading file"
            :confirmationMessages {:title "Load file as **coder**"
                                   :message "Load file: /a/b.clj in **clj** REPL"}}
           (confirmation/prepared-invocation "clojure_load_file"
                                             {:filePath "/a/b.clj" :replSessionKey "clj" :who "coder"})))
    (is (= {:title "Load file as **unknown**"
            :message "Load file: /a/b.clj"}
           (:confirmationMessages (confirmation/prepared-invocation "clojure_load_file" {:filePath "/a/b.clj"})))
        "without a who and a session key"))
  (testing "clojure_edit_files"
    (is (= {:invocationMessage "Editing Clojure files"
            :confirmationMessages {:title "Edit Clojure Files"
                                   :message "1 edit across 1 file"}}
           (confirmation/prepared-invocation "clojure_edit_files" {:edits [{:filePath "/a.clj"}]})))
    (is (= "3 edits across 2 files"
           (get-in (confirmation/prepared-invocation "clojure_edit_files"
                                                     {:edits [{:filePath "/a.clj"} {:filePath "/a.clj"} {:filePath "/b.clj"}]})
                   [:confirmationMessages :message])))))

(deftest confirmations-name-the-manifest-tools
  (let [package-json (js/JSON.parse (fs/readFileSync (path/resolve "package.json") "utf8"))
        manifest-tools (set (map #(.-name %) (.. package-json -contributes -languageModelTools)))]
    (is (= manifest-tools (set (keys confirmation/confirmations)))
        "Every tool in package.json has its confirmation, and there are no others")))

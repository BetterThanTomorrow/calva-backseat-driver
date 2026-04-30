(ns tests.mcp.structural-editing-test
  (:require
   ["fs" :as fs]
   ["path" :as path]
   ["vscode" :as vscode]
   [cljs.test :refer [is testing]]
   [clojure.string :as string]
   [e2e.macros :refer [deftest-async]]
   [e2e.mcp-helpers :as mcp]
   [promesa.core :as p]))

(def ^:private test-file-name "structural_edit_target.clj")

(defn- test-file-path []
  (path/join (.-fsPath mcp/workspace-uri) test-file-name))

(defn- read-test-file+ []
  (p/do (p/delay 500)
        (fs/readFileSync (test-file-path) "utf8")))

(defn- delete-test-file! []
  (when (fs/existsSync (test-file-path))
    (fs/unlinkSync (test-file-path))))

(defn- count-consecutive-blank-lines
  "Returns the maximum number of consecutive blank lines in text"
  [text]
  (let [lines (string/split-lines text)]
    (->> lines
         (partition-by string/blank?)
         (filter #(string/blank? (first %)))
         (map count)
         (apply max 0))))

(defn- initial-file-content []
  (string/join "\n"
               ["(ns structural-edit-target)"
                ""
                "(defn add-numbers"
                "  \"Adds a and b\""
                "  [a b]"
                "  (+ a b))"
                ""
                "(defn subtract-numbers"
                "  \"Subtracts b from a\""
                "  [a b]"
                "  (- a b))"
                ""]))

(deftest-async structural-editing-tests
  (-> (p/let [{:keys [socket]} (mcp/start-mcp-session!)

              ;; Create test file
              file-path (test-file-path)
              create-result (mcp/call-tool socket 100 "clojure_create_file"
                                          {:filePath file-path
                                           :content (initial-file-content)})

              _ (js/console.log "[structural-edit] Create result:" (pr-str create-result))

              ;; === 1. Replace with trailing newlines — should NOT accumulate blank lines ===

              _ (js/console.log "[structural-edit] Testing replace with trailing newlines...")
              replace-result (mcp/call-tool socket 101 "replace_top_level_form"
                                           {:filePath file-path
                                            :line 3
                                            :targetLineText "(defn add-numbers"
                                            :newForm "(defn add-numbers\n  \"Adds a and b\"\n  [a b]\n  (+ a b))\n\n\n"})

              _ (js/console.log "[structural-edit] Replace result:" (pr-str replace-result))
              content-after-replace (read-test-file+)
              _ (js/console.log "[structural-edit] Content after replace:" (pr-str content-after-replace))

              ;; === 2. Replace again to test idempotency ===

              replace-result-2 (mcp/call-tool socket 102 "replace_top_level_form"
                                             {:filePath file-path
                                              :line 3
                                              :targetLineText "(defn add-numbers"
                                              :newForm "(defn add-numbers\n  \"Adds a and b\"\n  [a b]\n  (+ a b))\n\n"})

              content-after-replace-2 (read-test-file+)
              _ (js/console.log "[structural-edit] Content after second replace:" (pr-str content-after-replace-2))

              ;; === 3. Insert form — should have proper spacing ===

              _ (js/console.log "[structural-edit] Testing insert...")
              insert-result (mcp/call-tool socket 103 "insert_top_level_form"
                                          {:filePath file-path
                                           :line 3
                                           :targetLineText "(defn add-numbers"
                                           :newForm "(defn multiply-numbers\n  \"Multiplies a and b\"\n  [a b]\n  (* a b))\n\n"})

              _ (js/console.log "[structural-edit] Insert result:" (pr-str insert-result))
              content-after-insert (read-test-file+)
              _ (js/console.log "[structural-edit] Content after insert:" (pr-str content-after-insert))

              ;; === 4. Append code — should not produce extra blank lines ===

              _ (js/console.log "[structural-edit] Testing append...")
              append-result (mcp/call-tool socket 104 "clojure_append_code"
                                          {:filePath file-path
                                           :code "(defn divide-numbers\n  \"Divides a by b\"\n  [a b]\n  (/ a b))\n\n\n"})

              _ (js/console.log "[structural-edit] Append result:" (pr-str append-result))
              content-after-append (read-test-file+)
              _ (js/console.log "[structural-edit] Content after append:" (pr-str content-after-append))

              _ (mcp/stop-mcp-session! socket)]

        ;; === Assertions ===

        (testing "create_file creates file successfully"
          (is (:success create-result)
              "File creation should succeed"))

        (testing "replace_top_level_form trims trailing whitespace"
          (is (:success replace-result)
              "Replace should succeed")
          (is (<= (count-consecutive-blank-lines content-after-replace) 1)
              "Replace should not leave more than 1 consecutive blank line"))

        (testing "replace_top_level_form is idempotent for whitespace"
          (is (:success replace-result-2)
              "Second replace should succeed")
          (is (<= (count-consecutive-blank-lines content-after-replace-2) 1)
              "Repeated replace should not accumulate blank lines")
          (is (= content-after-replace content-after-replace-2)
              "Replacing with same content should be idempotent"))

        (testing "insert_top_level_form produces proper spacing"
          (is (:success insert-result)
              "Insert should succeed")
          (is (<= (count-consecutive-blank-lines content-after-insert) 1)
              "Insert should not leave more than 1 consecutive blank line")
          (is (string/includes? content-after-insert "(defn multiply-numbers")
              "Inserted form should appear in file"))

        (testing "clojure_append_code trims trailing whitespace"
          (is (:success append-result)
              "Append should succeed")
          (is (<= (count-consecutive-blank-lines content-after-append) 1)
              "Append should not leave more than 1 consecutive blank line")
          (is (string/includes? content-after-append "(defn divide-numbers")
              "Appended form should appear in file")))

      (p/catch (fn [e]
                 (js/console.error "[structural-edit] Error:" (.-message e) e)
                 (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                 (throw e)))
      (p/finally (fn []
                   (delete-test-file!)))))

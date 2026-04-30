(ns tests.mcp.structural-editing-test
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

(def ^:private test-file-name "structural_edit_target.clj")

(defn- test-file-path []
  (path/join (.-fsPath mcp/workspace-uri) test-file-name))

(defn- read-test-file+ [previous-content]
  (if (some? previous-content)
    ;; Poll until content changes from previous
    (wait-for+ #(let [content (fs/readFileSync (test-file-path) "utf8")]
                  (when (not= content previous-content)
                    content))
               :interval 10
               :timeout 5000
               :message "File content did not change within 5s")
    ;; No previous content — just read (file was just created)
    (p/resolved (fs/readFileSync (test-file-path) "utf8"))))

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

(defn- expect-tool-failure [result expected-fragment]
  (and (false? (:success result))
       (string/includes? (or (:error result) "") expected-fragment)))

(def ^:private tiny-file-name "structural_edit_tiny_target.clj")
(defn- tiny-file-path []
  (path/join (.-fsPath mcp/workspace-uri) tiny-file-name))

(defn- delete-tiny-file! []
  (when (fs/existsSync (tiny-file-path))
    (fs/unlinkSync (tiny-file-path))))

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
              content-after-replace (read-test-file+ nil)
              _ (js/console.log "[structural-edit] Content after replace:" (pr-str content-after-replace))

              ;; === 2. Replace again to test idempotency ===

              replace-result-2 (mcp/call-tool socket 102 "replace_top_level_form"
                                              {:filePath file-path
                                               :line 3
                                               :targetLineText "(defn add-numbers"
                                               :newForm "(defn add-numbers\n  \"Adds a and b\"\n  [a b]\n  (+ a b))\n\n"})

              content-after-replace-2 (read-test-file+ nil)
              _ (js/console.log "[structural-edit] Content after second replace:" (pr-str content-after-replace-2))

              ;; === 3. Insert form — should have proper spacing ===

              _ (js/console.log "[structural-edit] Testing insert...")
              insert-result (mcp/call-tool socket 103 "insert_top_level_form"
                                           {:filePath file-path
                                            :line 3
                                            :targetLineText "(defn add-numbers"
                                            :newForm "(defn multiply-numbers\n  \"Multiplies a and b\"\n  [a b]\n  (* a b))\n\n"})

              _ (js/console.log "[structural-edit] Insert result:" (pr-str insert-result))
              content-after-insert (read-test-file+ content-after-replace-2)
              _ (js/console.log "[structural-edit] Content after insert:" (pr-str content-after-insert))

              ;; === 4. Append code — should not produce extra blank lines ===

              _ (js/console.log "[structural-edit] Testing append...")
              append-result (mcp/call-tool socket 104 "clojure_append_code"
                                           {:filePath file-path
                                            :code "(defn divide-numbers\n  \"Divides a by b\"\n  [a b]\n  (/ a b))\n\n\n"})

              _ (js/console.log "[structural-edit] Append result:" (pr-str append-result))
              content-after-append (read-test-file+ content-after-insert)
              _ (js/console.log "[structural-edit] Content after append:" (pr-str content-after-append))

              ;; === 5. Delete form — should not leave extra blank lines ===

              _ (js/console.log "[structural-edit] Testing delete...")
              delete-result (mcp/call-tool socket 105 "replace_top_level_form"
                                           {:filePath file-path
                                            :line 8
                                            :targetLineText "(defn add-numbers"
                                            :newForm ""})

              _ (js/console.log "[structural-edit] Delete result:" (pr-str delete-result))
              content-after-delete (read-test-file+ content-after-append)
              _ (js/console.log "[structural-edit] Content after delete:" (pr-str content-after-delete))

              ;; === 6. Delete first form — should not leave extra blank lines ===
              _ (js/console.log "[structural-edit] Testing delete first form...")
              delete-first-result (mcp/call-tool socket 106 "replace_top_level_form"
                                                 {:filePath file-path
                                                  :line 3
                                                  :targetLineText "(defn multiply-numbers"
                                                  :newForm ""})
              _ (js/console.log "[structural-edit] Delete first result:" (pr-str delete-first-result))
              content-after-delete-first (read-test-file+ content-after-delete)
              _ (js/console.log "[structural-edit] Content after delete first:" (pr-str content-after-delete-first))

              ;; === 7. Delete last function form — should not leave extra blank lines ===
              ;; After step 6, file has: ns (1), subtract-numbers (3), divide-numbers (8)
              _ (js/console.log "[structural-edit] Testing delete last form...")
              delete-last-result (mcp/call-tool socket 107 "replace_top_level_form"
                                                {:filePath file-path
                                                 :line 8
                                                 :targetLineText "(defn divide-numbers"
                                                 :newForm ""})
              _ (js/console.log "[structural-edit] Delete last result:" (pr-str delete-last-result))
              content-after-delete-last (read-test-file+ content-after-delete-first)
              _ (js/console.log "[structural-edit] Content after delete last:" (pr-str content-after-delete-last))

              ;; === 8. Wrong targetLineText should fail clearly ===
              _ (js/console.log "[structural-edit] Testing wrong target text...")
              wrong-target-result (mcp/call-tool socket 108 "insert_top_level_form"
                                                 {:filePath file-path
                                                  :line 3
                                                  :targetLineText "(defn does-not-exist [x]"
                                                  :newForm "(defn nope [] :nope)"})
              _ (js/console.log "[structural-edit] Wrong target result:" (pr-str wrong-target-result))

              ;; === 9. Correct target text but far-away line should fail ===
              _ (js/console.log "[structural-edit] Testing fuzzy window miss...")
              fuzzy-window-miss-result (mcp/call-tool socket 109 "replace_top_level_form"
                                                      {:filePath file-path
                                                       :line 999
                                                       :targetLineText "(defn subtract-numbers"
                                                       :newForm "(defn subtract-numbers [a b] (- a b))"})
              _ (js/console.log "[structural-edit] Fuzzy window miss result:" (pr-str fuzzy-window-miss-result))
              ;; === 10. Tiny file replace should work ===
              _ (js/console.log "[structural-edit] Testing tiny file...")
              tiny-create-result (mcp/call-tool socket 110 "clojure_create_file"
                                                {:filePath (tiny-file-path)
                                                 :content "(ns structural-edit-tiny-target)\n(def x 1)\n"})
              tiny-replace-result (mcp/call-tool socket 111 "replace_top_level_form"
                                                 {:filePath (tiny-file-path)
                                                  :line 2
                                                  :targetLineText "(def x 1)"
                                                  :newForm "(def x 2)"})
              _ (js/console.log "[structural-edit] Tiny replace result:" (pr-str tiny-replace-result))
              tiny-content-after-replace (wait-for+ #(let [content (fs/readFileSync (tiny-file-path) "utf8")]
                                                      (when (string/includes? content "(def x 2)")
                                                        content))
                                                    :interval 10
                                                    :timeout 5000
                                                    :message "Tiny file content did not update within 5s")

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
              "Appended form should appear in file"))

        (testing "replace_top_level_form with empty string deletes form"
          (is (:success delete-result)
              "Delete should succeed")
          (is (not (string/includes? content-after-delete "(defn add-numbers"))
              "Deleted form should not appear in file")
          (is (<= (count-consecutive-blank-lines content-after-delete) 1)
              "Delete should not leave more than 1 consecutive blank line")
          (is (string/includes? content-after-delete "(defn multiply-numbers")
              "Other forms should remain after delete")
          (is (string/includes? content-after-delete "(defn subtract-numbers")
              "Other forms should remain after delete"))

        (testing "delete first form keeps spacing stable"
          (is (:success delete-first-result))
          (is (<= (count-consecutive-blank-lines content-after-delete-first) 1))
          (is (not (string/includes? content-after-delete-first "(defn multiply-numbers"))))

        (testing "delete last form keeps spacing stable"
          (is (:success delete-last-result))
          (is (<= (count-consecutive-blank-lines content-after-delete-last) 1))
          (is (not (string/includes? content-after-delete-last "(defn divide-numbers"))))

        (testing "wrong targetLineText returns clear error"
          (is (expect-tool-failure wrong-target-result "Target line text not found")))

        (testing "line outside fuzzy window fails even with matching text"
          (is (expect-tool-failure fuzzy-window-miss-result "Target line text not found")))

        (testing "tiny file replace works"
          (is (:success tiny-create-result))
          (is (:success tiny-replace-result))
          (is (string/includes? tiny-content-after-replace "(def x 2)"))))

      (p/catch (fn [e]
                 (js/console.error "[structural-edit] Error:" (.-message e) e)
                 (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                 (throw e)))
      (p/finally (fn []
                   (delete-test-file!)
                   (delete-tiny-file!)))))

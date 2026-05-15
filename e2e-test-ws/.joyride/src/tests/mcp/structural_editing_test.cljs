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
    ;; Ignore empty string — transient state from truncate-before-write on Linux
    (wait-for+ #(let [content (fs/readFileSync (test-file-path) "utf8")]
                  (when (and (seq content)
                             (not= content previous-content))
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

(defn- active-editor-path []
  (some-> vscode/window .-activeTextEditor .-document .-uri .-fsPath))

(defn- file-in-visible-editors? [file-path]
  (some #(= (.-fsPath (.-uri (.-document ^js %))) file-path)
        vscode/window.visibleTextEditors))

(defn- activate-decoy-editor+ []
  (let [uri (vscode/Uri.joinPath mcp/workspace-uri ".vscode" "settings.json")]
    (p/let [document (vscode/workspace.openTextDocument uri)
            _ (vscode/window.showTextDocument document #js {:preview false})
            _ (wait-for+ #(when (= (.-fsPath uri) (active-editor-path))
                            true)
                         :interval 10
                         :timeout 5000
                         :message "Decoy editor did not become active within 5s")]
      nil)))

;; --- Test scenario helpers ---

(defn- test-create-file+ [socket file-path]
  (p/let [result (mcp/call-tool socket 100 "clojure_create_file"
                                {:filePath file-path
                                 :content (initial-file-content)})]
    (testing "create_file creates file successfully"
      (is (:success result) "File creation should succeed"))))

(defn- edit-with-decoy-and-verify+
  "Activate decoy editor, perform edit, verify active editor unchanged and file not visible."
  [socket file-path tool-call-fn prev-content]
  (p/let [_ (activate-decoy-editor+)
          active-before (active-editor-path)
          result (tool-call-fn)
          content (read-test-file+ prev-content)
          active-after (active-editor-path)]
    {:result result :content content
     :active-before active-before :active-after active-after}))

(defn- assert-invisible-edit [{:keys [active-before active-after]} file-path]
  (is (not= active-before file-path)
      "Edit should run while a different file is active")
  (is (not (file-in-visible-editors? file-path))
      "Edited file should not appear in any visible editor")
  (is (= active-after active-before)
      "Active editor should remain unchanged"))

(defn- test-replace-trims-whitespace+ [socket file-path]
  (p/let [{:keys [result content] :as ctx}
          (edit-with-decoy-and-verify+
           socket file-path
           #(mcp/call-tool socket 101 "replace_top_level_form"
                           {:filePath file-path
                            :line 3
                            :targetLineText "(defn add-numbers"
                            :newForm "(defn add-numbers\n  \"Adds a and b\"\n  [a b]\n  (+ a b))\n\n\n"})
           nil)]
    (testing "replace_top_level_form trims trailing whitespace"
      (assert-invisible-edit ctx file-path)
      (is (:success result) "Replace should succeed")
      (is (<= (count-consecutive-blank-lines content) 1)
          "Replace should not leave more than 1 consecutive blank line"))))

(defn- test-replace-idempotent+ [socket file-path]
  (p/let [content-before (read-test-file+ nil)
          result (mcp/call-tool socket 102 "replace_top_level_form"
                                {:filePath file-path
                                 :line 3
                                 :targetLineText "(defn add-numbers"
                                 :newForm "(defn add-numbers\n  \"Adds a and b\"\n  [a b]\n  (+ a b))\n\n"})
          content-after (read-test-file+ nil)]
    (testing "replace_top_level_form is idempotent for whitespace"
      (is (:success result) "Second replace should succeed")
      (is (<= (count-consecutive-blank-lines content-after) 1)
          "Repeated replace should not accumulate blank lines")
      (is (= content-before content-after)
          "Replacing with same content should be idempotent"))))

(defn- test-insert-proper-spacing+ [socket file-path]
  (p/let [content-before (read-test-file+ nil)
          {:keys [result content] :as ctx}
          (edit-with-decoy-and-verify+
           socket file-path
           #(mcp/call-tool socket 103 "insert_top_level_form"
                           {:filePath file-path
                            :line 3
                            :targetLineText "(defn add-numbers"
                            :newForm "(defn multiply-numbers\n  \"Multiplies a and b\"\n  [a b]\n  (* a b))\n\n"})
           content-before)]
    (testing "insert_top_level_form produces proper spacing"
      (assert-invisible-edit ctx file-path)
      (is (:success result) "Insert should succeed")
      (is (<= (count-consecutive-blank-lines content) 1)
          "Insert should not leave more than 1 consecutive blank line")
      (is (string/includes? content "(defn multiply-numbers")
          "Inserted form should appear in file"))))

(defn- test-append-trims-whitespace+ [socket file-path]
  (p/let [content-before (read-test-file+ nil)
          result (mcp/call-tool socket 104 "clojure_append_code"
                                {:filePath file-path
                                 :code "(defn divide-numbers\n  \"Divides a by b\"\n  [a b]\n  (/ a b))\n\n\n"})
          content (read-test-file+ content-before)
          active-after (active-editor-path)]
    (testing "clojure_append_code trims trailing whitespace"
      (is (:success result) "Append should succeed")
      (is (<= (count-consecutive-blank-lines content) 1)
          "Append should not leave more than 1 consecutive blank line")
      (is (string/includes? content "(defn divide-numbers")
          "Appended form should appear in file")
      (is (not (file-in-visible-editors? file-path))
          "Edited file should not appear in any visible editor after append"))))

(defn- test-delete-form+ [socket file-path]
  (p/let [content-before (read-test-file+ nil)
          result (mcp/call-tool socket 105 "replace_top_level_form"
                                {:filePath file-path
                                 :line 8
                                 :targetLineText "(defn add-numbers"
                                 :newForm ""})
          content (read-test-file+ content-before)]
    (testing "replace_top_level_form with empty string deletes form"
      (is (:success result) "Delete should succeed")
      (is (not (string/includes? content "(defn add-numbers"))
          "Deleted form should not appear in file")
      (is (<= (count-consecutive-blank-lines content) 1)
          "Delete should not leave more than 1 consecutive blank line")
      (is (string/includes? content "(defn multiply-numbers")
          "Other forms should remain after delete")
      (is (string/includes? content "(defn subtract-numbers")
          "Other forms should remain after delete")
      (is (not (file-in-visible-editors? file-path))
          "Edited file should not appear in any visible editor after delete"))))

(defn- delete-and-verify-gone+
  "Delete a form by replacing with empty string and verify it's gone from file."
  [socket file-path {:keys [id line target-text form-name test-label]}]
  (p/let [content-before (read-test-file+ nil)
          result (mcp/call-tool socket id "replace_top_level_form"
                                {:filePath file-path
                                 :line line
                                 :targetLineText target-text
                                 :newForm ""})
          content (read-test-file+ content-before)]
    (testing test-label
      (is (:success result))
      (is (<= (count-consecutive-blank-lines content) 1))
      (is (not (string/includes? content form-name))))))

(defn- test-delete-first-form+ [socket file-path]
  (delete-and-verify-gone+ socket file-path
    {:id 106 :line 3 :target-text "(defn multiply-numbers"
     :form-name "(defn multiply-numbers"
     :test-label "delete first form keeps spacing stable"}))

(defn- test-delete-last-form+ [socket file-path]
  (delete-and-verify-gone+ socket file-path
    {:id 107 :line 8 :target-text "(defn divide-numbers"
     :form-name "(defn divide-numbers"
     :test-label "delete last form keeps spacing stable"}))

(defn- test-wrong-target-text+ [socket file-path]
  (p/let [result (mcp/call-tool socket 108 "insert_top_level_form"
                                {:filePath file-path
                                 :line 3
                                 :targetLineText "(defn does-not-exist [x]"
                                 :newForm "(defn nope [] :nope)"})]
    (testing "wrong targetLineText returns clear error"
      (is (expect-tool-failure result "Target line text not found")))))

(defn- test-fuzzy-window-miss+ [socket file-path]
  (p/let [result (mcp/call-tool socket 109 "replace_top_level_form"
                                {:filePath file-path
                                 :line 999
                                 :targetLineText "(defn subtract-numbers"
                                 :newForm "(defn subtract-numbers [a b] (- a b))"})]
    (testing "line outside fuzzy window fails even with matching text"
      (is (expect-tool-failure result "Target line text not found")))))

(defn- test-tiny-file+ [socket]
  (p/let [create-result (mcp/call-tool socket 110 "clojure_create_file"
                                       {:filePath (tiny-file-path)
                                        :content "(ns structural-edit-tiny-target)\n(def x 1)\n"})
          replace-result (mcp/call-tool socket 111 "replace_top_level_form"
                                        {:filePath (tiny-file-path)
                                         :line 2
                                         :targetLineText "(def x 1)"
                                         :newForm "(def x 2)"})
          content (wait-for+ #(let [c (fs/readFileSync (tiny-file-path) "utf8")]
                                (when (string/includes? c "(def x 2)") c))
                             :interval 10
                             :timeout 5000
                             :message "Tiny file content did not update within 5s")]
    (testing "tiny file replace works"
      (is (:success create-result))
      (is (:success replace-result))
      (is (string/includes? content "(def x 2)")))))

;; --- Test orchestrator ---

(deftest-async structural-editing-tests
  (-> (p/let [{:keys [socket]} (mcp/start-mcp-session!)
              file-path (test-file-path)]
        (p/do (test-create-file+ socket file-path)
              (test-replace-trims-whitespace+ socket file-path)
              (test-replace-idempotent+ socket file-path)
              (test-insert-proper-spacing+ socket file-path)
              (test-append-trims-whitespace+ socket file-path)
              (test-delete-form+ socket file-path)
              (test-delete-first-form+ socket file-path)
              (test-delete-last-form+ socket file-path)
              (test-wrong-target-text+ socket file-path)
              (test-fuzzy-window-miss+ socket file-path)
              (test-tiny-file+ socket)
              (mcp/stop-mcp-session! socket)))
      (p/catch (fn [e]
                 (js/console.error "[structural-edit] Error:" (.-message e) e)
                 (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                 (throw e)))
      (p/finally (fn []
                   (delete-test-file!)
                   (delete-tiny-file!)))))

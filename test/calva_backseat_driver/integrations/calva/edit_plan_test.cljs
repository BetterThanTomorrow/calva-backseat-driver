(ns calva-backseat-driver.integrations.calva.edit-plan-test
  (:require [cljs.test :refer [deftest testing is]]
            [calva-backseat-driver.integrations.calva.edit-plan :as edit-plan]))

(def ^:private document
  {:document/text "(ns edit-target)\n\n(defn add-numbers\n  [a b]\n  (+ a b))\n\n(defn subtract-numbers\n  [a b]\n  (- a b))\n"})

(def ^:private add-numbers-range [[2 0] [4 10]])
(def ^:private subtract-numbers-range [[6 0] [8 10]])

(def ^:private replace-edit
  {:editor/line-number 3
   :editor/target-line "(defn add-numbers"
   :editor/new-form "(defn add-numbers\n  [x y]\n  (+ x y))"
   :editor/ranges-fn-key :currentTopLevelForm
   :editor/search-padding 2
   :editor/context-padding 10})

(deftest input-error
  (is (nil? (edit-plan/input-error replace-edit)))
  (is (= {:success false
          :saved false
          :error "No target line text provided. Provide an empty string to target an empty line."}
         (edit-plan/input-error (dissoc replace-edit :editor/target-line))))
  (is (= "No insert/replace text provided."
         (:error (edit-plan/input-error (dissoc replace-edit :editor/new-form)))))
  (is (= "Target line text cannot start with a comment (;). You can only target forms/sexpressions. (To edit line comments, use your line based editing tools.)"
         (:error (edit-plan/input-error (assoc replace-edit :editor/target-line "  ; a comment")))))
  (is (= "Replacement form cannot start with a comment (;). You can only insert forms/sexpressions with this tool. (To edit line comments, use your line based editing tools.)"
         (:error (edit-plan/input-error (assoc replace-edit :editor/new-form "; a comment")))))
  (is (nil? (edit-plan/input-error (assoc replace-edit :editor/target-line "" :editor/new-form "")))
      "An empty target line and an empty new form are fine"))

(deftest targeting
  (is (= 3 (edit-plan/target-line-number document replace-edit))
      "The target at its line")
  (is (= 3 (edit-plan/target-line-number document (assoc replace-edit :editor/line-number 5)))
      "A target two lines from the line it was given, inside the search padding")
  (is (nil? (edit-plan/target-line-number document (assoc replace-edit :editor/line-number 6)))
      "and three lines from it, outside")
  (is (= {:success false
          :error "Target line text not found. Expected: '(defn no-such-function' near line 3"
          :remedy "The target text was not found in the context shown below. Read the file to understand the actual structure, then target the correct line with the first line of the existing top level form starting at that line."
          :file-context " 2 | \n→3 | (defn add-numbers\n 4 |   [a b]"}
         (edit-plan/target-not-found document (assoc replace-edit
                                                    :editor/target-line "(defn no-such-function"
                                                    :editor/context-padding 1)))
      "A target that isn't found gets the lines around where it was looked for")
  (is (re-find #"^Line 7 in the context below matches your target text"
               (:remedy (edit-plan/target-not-found document (assoc replace-edit
                                                                    :editor/target-line "(defn subtract-numbers"
                                                                    :editor/search-padding 1))))
      "A target that is in the context, outside the search padding, is pointed out")
  (is (nil? (edit-plan/form-mismatch document replace-edit "(defn add-numbers\n  [a b]\n  (+ a b))"))
      "A target that starts the top level form at its line")
  (is (nil? (edit-plan/form-mismatch document (assoc replace-edit :editor/target-line "(defn add-numbers ; adds")
                                     "(defn add-numbers\n  [a b]\n  (+ a b))"))
      "also with a trailing comment that the form doesn't have")
  (let [mismatch (edit-plan/form-mismatch document (assoc replace-edit
                                                          :editor/line-number 4
                                                          :editor/target-line "[a b]")
                                          "(defn add-numbers\n  [a b]\n  (+ a b))")]
    (is (= {:success false
            :error "Target text validation failed near line: 4"
            :validation-error "The target text does not match the first line of a top level form in the vicinity of the target line."}
           (select-keys mismatch [:success :error :validation-error]))
        "A target inside a form isn't the start of a top level form")
    (is (string? (:file-context mismatch)))))

(deftest the-edit-plan
  (testing "a replace"
    (is (= {:editor/range add-numbers-range
            :editor/text "(defn add-numbers\n  [x y]\n  (+ x y))"}
           (edit-plan/edit-plan document add-numbers-range
                                (update replace-edit :editor/new-form #(str "\n" % "\n  "))))
        "The form's range gets the new form, trimmed"))
  (testing "an insert"
    (is (= {:editor/range [[6 0] [6 0]]
            :editor/text "(def x 1)\n\n"}
           (edit-plan/edit-plan document [[6 0] [6 0]]
                                {:editor/new-form "(def x 1)"
                                 :editor/ranges-fn-key :insertionPoint}))
        "The new form goes in at the insertion point, with a blank line after it"))
  (testing "a replace with nothing, which removes the form"
    (is (= {:editor/range [[1 0] [5 0]]
            :editor/text ""}
           (edit-plan/edit-plan document add-numbers-range (assoc replace-edit :editor/new-form "")))
        "The blank line before the form and the rest through the start of the next line go with it")
    (is (= {:editor/range [[5 0] [9 0]]
            :editor/text ""}
           (edit-plan/edit-plan document subtract-numbers-range (assoc replace-edit :editor/new-form "  ")))
        "also for the last form of a document that ends with a newline")
    (is (= {:editor/range [[0 0] [1 0]]
            :editor/text ""}
           (edit-plan/edit-plan document [[0 0] [0 16]] (assoc replace-edit :editor/new-form "")))
        "The first form has no line before it")
    (is (= {:editor/range [[0 0] [0 9]]
            :editor/text ""}
           (edit-plan/edit-plan {:document/text "(def x 1)"} [[0 0] [0 9]] (assoc replace-edit :editor/new-form "")))
        "A form that is the whole document has no next line either")
    (is (= [[2 0] [5 0]]
           (:editor/range (edit-plan/edit-plan {:document/text "(ns a)\n(def y 2)\n(defn add-numbers\n  [a b]\n  (+ a b))\n"}
                                               add-numbers-range
                                               (assoc replace-edit :editor/new-form ""))))
        "A line before the form that isn't blank stays")))

(deftest the-append-plan
  (is (= {:editor/range [[10 0] [10 0]]
          :editor/text "\n(def x 1)\n"}
         (edit-plan/append-plan document "  (def x 1)\n"))
      "After a document that ends with a newline, the code follows one blank line")
  (is (= {:editor/range [[1 0] [1 0]]
          :editor/text "\n\n(def x 1)\n"}
         (edit-plan/append-plan {:document/text "(ns a)"} "(def x 1)"))
      "After a document that ends in the middle of a line, the code gets a blank line before it")
  (is (= {:editor/range [[1 0] [1 0]]
          :editor/text "\n(def x 1)\n"}
         (edit-plan/append-plan {:document/text ""} "(def x 1)"))
      "An empty document"))

(deftest after-the-edit
  (testing "what waits for the edit to show in the document"
    (is (true? (edit-plan/awaits-visibility? {:editor/edit-applied? true :editor/was-dirty? false})))
    (is (false? (edit-plan/awaits-visibility? {:editor/edit-applied? true :editor/was-dirty? true}))
        "A document that had unsaved changes isn't saved, so nothing waits")
    (is (false? (edit-plan/awaits-visibility? {:editor/edit-applied? true :editor/was-dirty? false :editor/identical-replace? true}))
        "A replace with the same text shows nothing")
    (is (false? (edit-plan/awaits-visibility? {:editor/edit-applied? false :editor/was-dirty? false}))))
  (testing "an edit that isn't saved"
    (is (= {:success false :error "Failed to apply edit" :saved false}
           (edit-plan/unsaved-result {:editor/edit-applied? false :editor/was-dirty? true})))
    (is (= {:success true
            :saved false
            :note "The file had unsaved changes in the editor; the edit is in the editor buffer, not on disk. Read the open document, not the disk."}
           (edit-plan/unsaved-result {:editor/edit-applied? true :editor/was-dirty? true})))
    (is (= {:success true
            :saved false
            :note "The edit may be in the editor buffer, but it was not confirmed visible in time so the file was not saved. Read the open document, not the disk."}
           (edit-plan/unsaved-result {:editor/edit-applied? true :editor/was-dirty? false :editor/edit-visible? false}))))
  (testing "an edit that is saved"
    (is (nil? (edit-plan/unsaved-result {:editor/edit-applied? true :editor/was-dirty? false :editor/edit-visible? true})))
    (is (nil? (edit-plan/unsaved-result {:editor/edit-applied? true :editor/was-dirty? false
                                         :editor/identical-replace? true :editor/edit-visible? false}))
        "A replace with the same text is saved without having shown")
    (is (nil? (edit-plan/unsaved-result {:editor/edit-applied? true :editor/was-dirty? false}))
        "and so is an edit whose visibility wasn't waited for"))
  (testing "after the save"
    (is (= {:success true :saved true}
           (edit-plan/saved-result {:editor/save-succeeded? true :editor/still-dirty? false})))
    (is (= {:success true
            :saved false
            :note "The edit is in the editor buffer, but the file on disk was not saved. Read the open document, not the disk."}
           (edit-plan/saved-result {:editor/save-succeeded? false :editor/still-dirty? true})
           (edit-plan/saved-result {:editor/save-succeeded? true :editor/still-dirty? true}))
        "A save that VS Code declined, and one that left the document dirty")
    (is (= "EACCES"
           (:error (edit-plan/saved-result {:editor/save-error "EACCES"})))
        "A save that threw gives its message too")
    (is (= {:success true
            :saved false
            :note "The edit is in the editor buffer, but the file on disk was not saved. Read the open document, not the disk."
            :error nil}
           (edit-plan/saved-result {:editor/save-error nil}))
        "also when what it threw had no message")))

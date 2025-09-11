(ns calva-backseat-driver.integrations.calva.editor-util-test
  (:require [cljs.test :refer [deftest testing is]]
            [calva-backseat-driver.integrations.calva.editor-util :as editor-util]))

(deftest find-target-line-by-text
  (let [sample-doc "line 0\n  line 1\nline 2\n    line 3\nline 4    "]
    (testing "finds exact match at specified line"
      (is (= 1 (editor-util/find-target-line-by-text sample-doc "line 1" 1 2))
          "finds exact match")
      (is (= 1 (editor-util/find-target-line-by-text sample-doc "line 1" 1 0))
          "finds exact match with no search window")
      (is (= 0 (editor-util/find-target-line-by-text "the only line" "the only line" 0 2))
          "finds exact match in one-line doc"))

    (testing "trims whitespace when comparing"
      (is (= 3 (editor-util/find-target-line-by-text sample-doc "line 3" 3 2))
          "should find match despite leading whitespace in document")
      (is (= 4 (editor-util/find-target-line-by-text sample-doc "line 4" 4 2))
          "should find match despite trailing whitespace in document"))

    (testing "text not found"
      (is (nil? (editor-util/find-target-line-by-text sample-doc "nonexistent" 1 2))
          "returns nil for non-existent text")
      (is (nil? (editor-util/find-target-line-by-text "" "text" 0 2))
          "returns nil for any text in empty doc")
      (is (nil? (editor-util/find-target-line-by-text "" "anything" 0 2))
          "returns nil for empty document"))

    (testing "search at document boundaries"
      (is (= 0 (editor-util/find-target-line-by-text sample-doc "line 0" 0 2))
          "should find match at beginning of document")
      (is (= 4 (editor-util/find-target-line-by-text sample-doc "line 4" 4 2))
          "should find match at end of document"))))

(deftest search-window-behavior
  (let [large-doc "line 0\nline 1\nline 2\nline 3\nline 4\nline 5\nline 6\nline 7\nline 8\nline 9"]
    (testing "finds text within search window"
      (is (= 2 (editor-util/find-target-line-by-text large-doc "line 2" 0 2))
          "should find line 2 from position 0 (within window of 2)")
      (is (= 6 (editor-util/find-target-line-by-text large-doc "line 6" 4 2))
          "should find line 6 from position 4 (within window of 2)"))

    (testing "does not find text outside search window"
      (is (nil? (editor-util/find-target-line-by-text large-doc "line 8" 4 2))
          "should not find line 8 from position 4 (outside window of 2)")
      (is (nil? (editor-util/find-target-line-by-text large-doc "line 5" 1 2))
          "should not find line 5 from position 1 (outside window of 2)"))

    (testing "search window extends beyond document boundaries"
      (let [short-doc "line 0\nline 1\nline 2"]
        (is (= 2 (editor-util/find-target-line-by-text short-doc "line 2" 1 2))
            "should handle search window extending beyond end of document")
        (is (= 0 (editor-util/find-target-line-by-text short-doc "line 0" 1 2))
            "should handle search window extending before start of document")))))

(deftest line-endings
  (let [windows-doc "line 0\r\nline 1\r\nline 2"]
    (is (= 1 (editor-util/find-target-line-by-text windows-doc "line 1" 1 2))
        "should handle Windows CRLF line endings"))

  (let [unix-doc "line 0\nline 1\nline 2"]
    (is (= 1 (editor-util/find-target-line-by-text unix-doc "line 1" 1 2))
        "should handle Unix LF line endings"))

  (let [mixed-doc "line 0\r\nline 1\nline 2"]
    (is (= 1 (editor-util/find-target-line-by-text mixed-doc "line 1" 1 2))
        "should handle mixed line endings")))

(deftest whitespace-handling
  (let [whitespace-doc "  \t  line with leading whitespace  \t  \n\tline with tab\t\n    indented line    "]
    (is (= 0 (editor-util/find-target-line-by-text whitespace-doc "line with leading whitespace" 0 2))
        "trims leading and trailing whitespace")

    (is (= 1 (editor-util/find-target-line-by-text whitespace-doc "line with tab" 1 2))
        "handles tabs and mixed whitespace")

    (is (= 2 (editor-util/find-target-line-by-text whitespace-doc "indented line" 2 2))
        "handles indented content")))
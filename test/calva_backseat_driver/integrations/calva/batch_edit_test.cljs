(ns calva-backseat-driver.integrations.calva.batch-edit-test
  (:require [cljs.test :refer [deftest testing is]]
            [calva-backseat-driver.integrations.calva.batch-edit :as batch-edit]))

(deftest validate-edit-schema-valid-edits-test
  (testing "replace edit"
    (is (nil? (batch-edit/validate-edit-schema
               [{:type "replace" :filePath "/foo/bar.clj" :line 5 :targetLineText "(defn foo" :newForm "(defn foo\n  [])"}]))))
  (testing "insert edit"
    (is (nil? (batch-edit/validate-edit-schema
               [{:type "insert" :filePath "/foo/bar.clj" :line 10 :targetLineText "(defn bar" :newForm "(defn baz\n  [])"}]))))
  (testing "append edit"
    (is (nil? (batch-edit/validate-edit-schema
               [{:type "append" :filePath "/foo/bar.clj" :code "(defn qux [])"}]))))
  (testing "create edit"
    (is (nil? (batch-edit/validate-edit-schema
               [{:type "create" :filePath "/foo/new.clj" :content "(ns foo.new)"}]))))
  (testing "mixed valid edits"
    (is (nil? (batch-edit/validate-edit-schema
               [{:type "replace" :filePath "/a.clj" :line 1 :targetLineText "(ns a)" :newForm "(ns a)"}
                {:type "insert" :filePath "/a.clj" :line 5 :targetLineText "(defn x" :newForm "(defn y [])"}
                {:type "append" :filePath "/b.clj" :code "(defn z [])"}
                {:type "create" :filePath "/c.clj" :content "(ns c)"}]))))
  (testing "one create + one append per file is fine"
    (is (nil? (batch-edit/validate-edit-schema [{:type "create" :filePath "/a.clj" :content "(ns a)"}
                                                {:type "append" :filePath "/a.clj" :code "(defn x [])"}])))))

(deftest validate-edit-schema-error-cases-test
  (testing "missing type returns error"
    (let [result (batch-edit/validate-edit-schema [{:filePath "/foo/bar.clj"}])]
      (is (some? result))
      (is (= 1 (count result)))
      (is (= 0 (:index (first result))))))

  (testing "invalid type returns error"
    (let [result (batch-edit/validate-edit-schema [{:type "delete" :filePath "/foo/bar.clj"}])]
      (is (some? result))
      (is (= 1 (count result)))))

  (testing "missing filePath returns error"
    (let [result (batch-edit/validate-edit-schema [{:type "append" :code "(defn x [])"}])]
      (is (some? result))
      (is (= 1 (count result)))))

  (testing "relative filePath returns error"
    (let [result (batch-edit/validate-edit-schema [{:type "append" :filePath "relative/path.clj" :code "(defn x [])"}])]
      (is (some? result))
      (is (= 1 (count result)))))

  (testing "replace missing line returns error"
    (let [result (batch-edit/validate-edit-schema [{:type "replace" :filePath "/a.clj" :targetLineText "(defn foo" :newForm "(defn foo [])"}])]
      (is (some? result))))

  (testing "replace missing targetLineText returns error"
    (let [result (batch-edit/validate-edit-schema [{:type "replace" :filePath "/a.clj" :line 5 :newForm "(defn foo [])"}])]
      (is (some? result))))

  (testing "replace missing newForm returns error"
    (let [result (batch-edit/validate-edit-schema [{:type "replace" :filePath "/a.clj" :line 5 :targetLineText "(defn foo"}])]
      (is (some? result))))

  (testing "append missing code returns error"
    (let [result (batch-edit/validate-edit-schema [{:type "append" :filePath "/a.clj"}])]
      (is (some? result))))

  (testing "create missing content returns error"
    (let [result (batch-edit/validate-edit-schema [{:type "create" :filePath "/a.clj"}])]
      (is (some? result))))

  (testing "multiple creates for same file returns error"
    (let [result (batch-edit/validate-edit-schema [{:type "create" :filePath "/a.clj" :content "(ns a)"}
                                                   {:type "create" :filePath "/a.clj" :content "(ns a)"}])]
      (is (some? result))
      (is (some #(re-find #"Multiple create" (:error %)) result))))

  (testing "multiple appends for same file returns error"
    (let [result (batch-edit/validate-edit-schema [{:type "append" :filePath "/a.clj" :code "(defn x [])"}
                                                   {:type "append" :filePath "/a.clj" :code "(defn y [])"}])]
      (is (some? result))
      (is (some #(re-find #"Multiple append" (:error %)) result)))))

(deftest sort-edits-for-file-test
  (testing "creates come first"
    (let [edits [{:type "replace" :filePath "/a.clj" :line 3 :targetLineText "x" :newForm "x"}
                 {:type "create" :filePath "/a.clj" :content "y"}]
          sorted (batch-edit/sort-edits-for-file edits)]
      (is (= "create" (:type (first sorted))))))

  (testing "replace/insert sorted by line descending"
    (let [edits [{:type "replace" :filePath "/a.clj" :line 5 :targetLineText "x" :newForm "x"}
                 {:type "insert" :filePath "/a.clj" :line 20 :targetLineText "y" :newForm "y"}
                 {:type "replace" :filePath "/a.clj" :line 10 :targetLineText "z" :newForm "z"}]
          sorted (batch-edit/sort-edits-for-file edits)]
      (is (= [20 10 5] (map :line sorted)))))

  (testing "appends come last"
    (let [edits [{:type "append" :filePath "/a.clj" :code "a"}
                 {:type "replace" :filePath "/a.clj" :line 3 :targetLineText "x" :newForm "x"}]
          sorted (batch-edit/sort-edits-for-file edits)]
      (is (= "append" (:type (last sorted))))))

  (testing "mixed edits: create → replace(20) → replace(5) → append"
    (let [edits [{:type "replace" :filePath "/a.clj" :line 5 :targetLineText "a" :newForm "a"}
                 {:type "append" :filePath "/a.clj" :code "b"}
                 {:type "create" :filePath "/a.clj" :content "c"}
                 {:type "replace" :filePath "/a.clj" :line 20 :targetLineText "d" :newForm "d"}]
          sorted (batch-edit/sort-edits-for-file edits)]
      (is (= ["create" "replace" "replace" "append"] (map :type sorted)))
      (is (= 20 (:line (second sorted))))
      (is (= 5 (:line (nth sorted 2)))))))

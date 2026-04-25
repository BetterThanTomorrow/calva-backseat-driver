(ns calva-backseat-driver.mcp.skills-test
  (:require [cljs.test :refer [deftest testing is]]
            [calva-backseat-driver.mcp.skills :as skills]))

(deftest parse-skill-frontmatter-test
  (testing "single-quoted description"
    (is (= {:description "some text"}
           (skills/parse-skill-frontmatter "---\nname: test\ndescription: 'some text'\n---\n# Body"))))

  (testing "double-quoted description"
    (is (= {:description "some text"}
           (skills/parse-skill-frontmatter "---\nname: test\ndescription: \"some text\"\n---\n# Body"))))

  (testing "unquoted description"
    (is (= {:description "some text"}
           (skills/parse-skill-frontmatter "---\nname: test\ndescription: some text\n---\n# Body"))))

  (testing "frontmatter with full SKILL.md body content"
    (let [content "---\nname: my-skill\ndescription: 'A useful skill'\n---\n\n# My Skill\n\nSome detailed content here.\n\n## Section\n\nMore content."]
      (is (= {:description "A useful skill"}
             (skills/parse-skill-frontmatter content)))))

  (testing "no frontmatter returns nil"
    (is (nil? (skills/parse-skill-frontmatter "# Just a heading\n\nSome content."))))

  (testing "empty string returns nil"
    (is (nil? (skills/parse-skill-frontmatter "")))))

(deftest compose-instructions-test
  (let [test-skills [{:skill/name "backseat-driver"
                      :skill/description "REPL tools usage"}
                     {:skill/name "editing-clojure-files"
                      :skill/description "Structural editing"}]]

    (testing "repl enabled with skills mentions REPL tools and lists skills"
      (let [result (skills/compose-instructions true test-skills)]
        (is (string? result))
        (is (re-find #"clojure_evaluate_code" result) "mentions REPL eval tool")
        (is (re-find #"clojure_load_file" result) "mentions load file tool")
        (is (re-find #"resources/list" result) "mentions resources/list")
        (is (re-find #"resources/read" result) "mentions resources/read")
        (is (re-find #"backseat-driver" result) "lists first skill")
        (is (re-find #"editing-clojure-files" result) "lists second skill")))

    (testing "repl disabled with skills omits REPL text but lists skills"
      (let [result (skills/compose-instructions false test-skills)]
        (is (nil? (re-find #"clojure_evaluate_code" result)) "no REPL eval mention")
        (is (nil? (re-find #"clojure_load_file" result)) "no load file mention")
        (is (re-find #"resources/list" result) "still mentions resources/list")
        (is (re-find #"backseat-driver" result) "still lists skills")))

    (testing "empty skills omits skills section"
      (let [result (skills/compose-instructions true [])]
        (is (re-find #"structural editing" result) "has base content")
        (is (nil? (re-find #"resources/list" result)) "no resources mention")
        (is (nil? (re-find #"backseat-driver" result)) "no skill listed")))

    (testing "nil skills omits skills section"
      (let [result (skills/compose-instructions true nil)]
        (is (re-find #"structural editing" result) "has base content")
        (is (nil? (re-find #"resources/list" result)) "no resources mention")))))

(deftest filter-skills-test
  (let [all-skills [{:skill/name "backseat-driver"
                     :skill/description "REPL tools"}
                    {:skill/name "editing-clojure-files"
                     :skill/description "Structural editing"}]]

    (testing "all enabled returns all skills"
      (is (= 2 (count (skills/filter-skills all-skills
                                            {:provide-bd-skill? true
                                             :provide-edit-skill? true})))))

    (testing "BD skill disabled returns only editing skill"
      (let [result (skills/filter-skills all-skills
                                         {:provide-bd-skill? false
                                          :provide-edit-skill? true})]
        (is (= 1 (count result)))
        (is (= "editing-clojure-files" (:skill/name (first result))))))

    (testing "edit skill disabled returns only BD skill"
      (let [result (skills/filter-skills all-skills
                                         {:provide-bd-skill? true
                                          :provide-edit-skill? false})]
        (is (= 1 (count result)))
        (is (= "backseat-driver" (:skill/name (first result))))))

    (testing "both disabled returns empty"
      (is (empty? (skills/filter-skills all-skills
                                        {:provide-bd-skill? false
                                         :provide-edit-skill? false}))))

    (testing "unknown skill defaults to included"
      (let [skills-with-unknown (conj all-skills {:skill/name "future-skill"
                                                  :skill/description "New skill"})
            result (skills/filter-skills skills-with-unknown
                                         {:provide-bd-skill? true
                                          :provide-edit-skill? true})]
        (is (= 3 (count result)))
        (is (some #(= "future-skill" (:skill/name %)) result))))))

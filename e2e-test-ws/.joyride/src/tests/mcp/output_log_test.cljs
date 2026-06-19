(ns tests.mcp.output-log-test
  (:require
   ["vscode" :as vscode]
   [cljs.test :refer [is testing]]
   [e2e.macros :refer [deftest-async]]
   [e2e.mcp-helpers :as mcp]
   [promesa.core :as p]))

;; --- Domain-specific helpers ---

(defn- query-output-log
  "Query the output log tool and return parsed result."
  [socket id query & [inputs]]
  (mcp/call-tool socket id "clojure_repl_output_log"
                 (cond-> {:query query}
                   inputs (assoc :inputs inputs))))

(defn- get-max-line
  "Get the current max output line number (checkpoint)."
  [socket]
  (p/let [result (query-output-log socket 99
                   "[:find (max ?l) . :where [?e :output/line ?l]]")]
    (or result 0)))

(defn- evaluate-code
  "Evaluate code via the MCP evaluate tool."
  [socket id {:keys [code namespace session-key who description]}]
  (mcp/call-tool socket id "clojure_evaluate_code"
                 (cond-> {:code code
                          :namespace namespace
                          :replSessionKey session-key
                          :who who}
                   description (assoc :description description))))

(defn- wait-for-output
  "Poll the output log until pred returns truthy for the query result.
   Options map: :socket, :query, :inputs, :pred, :timeout (default 5000)."
  [{:keys [socket query inputs pred timeout] :or {timeout 5000}}]
  (let [start (.now js/Date)]
    (p/loop []
      (p/let [result (query-output-log socket 98 query inputs)]
        (if (pred result)
          result
          (if (> (- (.now js/Date) start) timeout)
            (throw (js/Error. (str "wait-for-output timed out after " timeout "ms")))
            (p/do (p/delay 100)
                  (p/recur))))))))

(defn- get-session-key
  "Discover a REPL session key via MCP list_sessions tool."
  [socket]
  (p/let [result (mcp/call-tool socket 97 "clojure_list_sessions" {})
          sessions (:sessions result)]
    (or (->> sessions
             (some (fn [s] (when (:currentRoutedTarget s) (:replSessionKey s)))))
        (:replSessionKey (first sessions)))))

;; --- Shared query ---

(def ^:private who-since-query
  "[:find [(pull ?e [*]) ...] :in $ ?since ?who :where [?e :output/line ?l] [(> ?l ?since)] [?e :output/who ?who]]")

;; --- Test scenario helpers ---

(defn- test-basic-query+ [socket session-key]
  (p/let [checkpoint (get-max-line socket)
          _ (evaluate-code socket 1
                          {:code "(+ 21 21)"
                           :namespace "user"
                           :session-key session-key
                           :who "e2e-output-basic"
                           :description "e2e basic query test"})
          rows (wait-for-output {:socket socket
                                 :query who-since-query
                                 :inputs [checkpoint "e2e-output-basic"]
                                 :pred seq})]
    (testing "Basic query: evaluating code produces queryable output"
      (is (seq rows) "Should return at least one output row for the evaluation")
      (is (some #(= "evaluationResults" (:category %)) rows)
          "Should include an evaluationResults entry")
      (is (every? #(> (:line %) checkpoint) rows)
          "All returned rows should be after the checkpoint"))))

(defn- test-who-isolation+ [socket session-key]
  (p/let [checkpoint (get-max-line socket)
          _ (evaluate-code socket 2
                          {:code "(+ 1 1)" :namespace "user"
                           :session-key session-key :who "e2e-agent-alpha"
                           :description "agent alpha eval"})
          _ (evaluate-code socket 3
                          {:code "(+ 2 2)" :namespace "user"
                           :session-key session-key :who "e2e-agent-beta"
                           :description "agent beta eval"})
          alpha-rows (wait-for-output {:socket socket :query who-since-query
                                       :inputs [checkpoint "e2e-agent-alpha"] :pred seq})
          beta-rows (wait-for-output {:socket socket :query who-since-query
                                      :inputs [checkpoint "e2e-agent-beta"] :pred seq})]
    (testing "Who isolation: different who slugs return separate results"
      (is (every? #(= "e2e-agent-alpha" (:who %)) alpha-rows)
          "Alpha rows should only contain alpha's output")
      (is (every? #(= "e2e-agent-beta" (:who %)) beta-rows)
          "Beta rows should only contain beta's output")
      (is (not= (set (map :line alpha-rows))
                (set (map :line beta-rows)))
          "Alpha and beta should have different line numbers"))))

(defn- test-who-validation+ [socket session-key]
  (p/let [ui-result (evaluate-code socket 4
                                   {:code "(+ 1 1)" :namespace "user"
                                    :session-key session-key :who "ui"})
          api-result (evaluate-code socket 5
                                    {:code "(+ 1 1)" :namespace "user"
                                     :session-key session-key :who "api"})
          blank-result (evaluate-code socket 6
                                      {:code "(+ 1 1)" :namespace "user"
                                       :session-key session-key :who ""})]
    (testing "Who validation: reserved values rejected"
      (is (some? (:error ui-result)) "who='ui' should return an error")
      (is (some? (:error api-result)) "who='api' should return an error"))
    (testing "Who validation: blank value rejected"
      (is (some? (:error blank-result)) "who='' (blank) should return an error"))))

(defn- eval-and-wait-for-rows+
  "Evaluate code with a unique who and wait for output rows to appear."
  [socket {:keys [id code session-key who description]}]
  (p/let [checkpoint (get-max-line socket)
          _ (evaluate-code socket id
                          {:code code :namespace "user"
                           :session-key session-key :who who
                           :description description})
          rows (wait-for-output {:socket socket :query who-since-query
                                 :inputs [checkpoint who] :pred seq})]
    rows))

(defn- test-output-categories+ [socket session-key]
  (p/let [rows (eval-and-wait-for-rows+ socket
                 {:id 7 :code "(* 3 7)" :session-key session-key
                  :who "e2e-category-test" :description "category pair test"})]
    (testing "Output categories: eval produces evaluationResults with who"
      (is (some #(= "evaluationResults" (:category %)) rows)
          "Should have an evaluationResults entry"))))

(defn- test-stdout-capture+ [socket session-key]
  (p/let [rows (eval-and-wait-for-rows+ socket
                 {:id 8 :code "(println \"e2e-stdout-marker\")" :session-key session-key
                  :who "e2e-stdout-test" :description "stdout capture test"})]
    (testing "Stdout capture: println produces evaluationOutput"
      (is (some #(= "evaluationOutput" (:category %)) rows)
          "Should have an evaluationOutput entry for stdout"))))

(defn- test-stderr-capture+ [socket session-key]
  (p/let [rows (eval-and-wait-for-rows+ socket
                 {:id 9 :code "(binding [*out* *err*] (println \"e2e-stderr-marker\"))"
                  :session-key session-key :who "e2e-stderr-test"
                  :description "stderr capture test"})]
    (testing "Stderr capture: writing to *err* produces output"
      (is (some #(or (= "evaluationErrorOutput" (:category %))
                     (= "evaluationOutput" (:category %)))
                rows)
          "Should have error output or output entry for stderr"))))

(defn- test-other-whos-since-last+ [socket session-key]
  (p/let [_ (evaluate-code socket 10
                          {:code "(+ 10 10)" :namespace "user"
                           :session-key session-key :who "e2e-cross-a"
                           :description "cross-who first"})
          _ (evaluate-code socket 11
                          {:code "(+ 20 20)" :namespace "user"
                           :session-key session-key :who "e2e-cross-b"
                           :description "cross-who interloper"})
          result (evaluate-code socket 12
                               {:code "(+ 30 30)" :namespace "user"
                                :session-key session-key :who "e2e-cross-a"
                                :description "cross-who second"})]
    (testing "otherWhosSinceLast: cross-who awareness"
      (is (some? (:other-whos-since-last result))
          "Should include other-whos-since-last field")
      (is (some #(= "e2e-cross-b" %) (:other-whos-since-last result))
          "Should list the interloping who slug"))))

(defn- test-category-filter+ [socket session-key]
  (p/let [checkpoint (get-max-line socket)
          _ (evaluate-code socket 13
                          {:code "(+ 99 1)" :namespace "user"
                           :session-key session-key :who "e2e-cat-filter"
                           :description "category filter test"})
          results-only (wait-for-output
                        {:socket socket
                         :query "[:find [(pull ?e [*]) ...] :in $ ?since ?who :where [?e :output/line ?l] [(> ?l ?since)] [?e :output/who ?who] [?e :output/category \"evaluationResults\"]]"
                         :inputs [checkpoint "e2e-cat-filter"]
                         :pred seq})]
    (testing "Category filter query: can filter by specific category"
      (is (every? #(= "evaluationResults" (:category %)) results-only)
          "Results-only query should return only evaluationResults"))))

(defn- test-aggregate-query+ [socket]
  (p/let [count-result (query-output-log socket 14
                                         "[:find (count ?e) . :in $ ?who :where [?e :output/who ?who]]"
                                         ["e2e-cat-filter"])]
    (testing "Aggregate query: count returns a scalar"
      (is (number? count-result) "Count query should return a number")
      (is (pos? count-result) "Count should be positive for a who with output"))))

(def ^:private results-category-query
  "[:find [(pull ?e [*]) ...] :in $ ?since ?who :where [?e :output/line ?l] [(> ?l ?since)] [?e :output/who ?who] [?e :output/category \"evaluationResults\"]]")

(defn- eval-image-and-query-content+
  "Evaluate image data URL, wait for output, then query with optional maxImages."
  [socket session-key {:keys [eval-id req-id who description max-images]}]
  (p/let [checkpoint (get-max-line socket)
          _ (evaluate-code socket eval-id
                          {:code "\"data:image/png;base64,iVBORw0KGgo=\""
                           :namespace "user"
                           :session-key session-key :who who
                           :description description})
          _ (wait-for-output {:socket socket :query results-category-query
                              :inputs [checkpoint who] :pred seq})
          resp (mcp/send-request socket
                                 {:jsonrpc "2.0" :id req-id
                                  :method "tools/call"
                                  :params {:name "clojure_repl_output_log"
                                           :arguments (cond-> {:query results-category-query
                                                               :inputs [checkpoint who]}
                                                        (some? max-images)
                                                        (assoc :maxImages max-images))}})]
    (get-in (js->clj resp :keywordize-keys true) [:result :content])))

(defn- test-image-content+ [socket session-key]
  (p/let [content (eval-image-and-query-content+ socket session-key
                    {:eval-id 20 :req-id 21 :who "e2e-output-image"
                     :description "output log image test" :max-images 1})]
    (testing "Image content: output log query with image data returns image content items"
      (is (= 2 (count content)) "Should have text + image content items")
      (is (= "text" (:type (first content))) "First content item should be text")
      (is (= "image" (:type (second content))) "Second content item should be image")
      (is (= "image/png" (:mimeType (second content))) "Image content should have correct MIME type")
      (is (string? (:data (second content))) "Image content should have base64 data string"))))

(defn- test-default-caps-images+ [socket session-key]
  (p/let [content (eval-image-and-query-content+ socket session-key
                    {:eval-id 30 :req-id 31 :who "e2e-output-cap"
                     :description "output log default cap test"})]
    (testing "Default caps images: returns only text by default (maxImages defaults to 0)"
      (is (= 1 (count content)) "Should have only text content (no images by default)")
      (is (= "text" (:type (first content))) "Content should be text type")
      (is (string? (re-find #"image-1-capped" (:text (first content))))
          "Text should contain capped image marker"))))

;; --- Test orchestrator ---

(deftest-async output-log-queries
  (let [backup-path (mcp/backup-settings! "output-log-test-backup.json")]
    (-> (p/let [_ (mcp/ensure-repl-and-eval-enabled!)
                {:keys [socket]} (mcp/start-mcp-session!)
                _ (mcp/wait-for-tool! socket "clojure_evaluate_code")
                session-key (get-session-key socket)]
          (p/do (test-basic-query+ socket session-key)
                (test-who-isolation+ socket session-key)
                (test-who-validation+ socket session-key)
                (test-output-categories+ socket session-key)
                (test-stdout-capture+ socket session-key)
                (test-stderr-capture+ socket session-key)
                (test-other-whos-since-last+ socket session-key)
                (test-category-filter+ socket session-key)
                (test-aggregate-query+ socket)
                (test-image-content+ socket session-key)
                (test-default-caps-images+ socket session-key)
                (mcp/stop-mcp-session! socket)))
        (p/catch (fn [e]
                   (js/console.error "[output-log] Error:" (.-message e) e)
                   (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                   (throw e)))
        (p/finally (fn []
                     (mcp/restore-settings! backup-path))))))

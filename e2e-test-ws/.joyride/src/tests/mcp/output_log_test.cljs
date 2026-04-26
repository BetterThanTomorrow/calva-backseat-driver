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
  "Poll the output log until pred returns truthy for the query result."
  [socket query inputs pred & {:keys [timeout] :or {timeout 15000}}]
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
             (some (fn [s] (when (:isActiveSession s) (:replSessionKey s)))))
        (:replSessionKey (first sessions)))))

;; --- Tests ---
;; All output log tests share a single MCP server session to avoid
;; settings/server lifecycle race conditions between async tests.

(deftest-async output-log-queries
  (let [backup-path (mcp/backup-settings! "output-log-test-backup.json")]
    (-> (p/let [_ (mcp/ensure-repl-and-eval-enabled!)
                {:keys [socket]} (mcp/start-mcp-session!)
                session-key (get-session-key socket)

                ;; === 1. Basic query (existing) ===
                checkpoint (get-max-line socket)

                _ (evaluate-code socket 1
                                 {:code "(+ 21 21)"
                                  :namespace "user"
                                  :session-key session-key
                                  :who "e2e-output-basic"
                                  :description "e2e basic query test"})

                basic-rows (wait-for-output
                            socket
                            "[:find [(pull ?e [*]) ...] :in $ ?since ?who :where [?e :output/line ?l] [(> ?l ?since)] [?e :output/who ?who]]"
                            [checkpoint "e2e-output-basic"]
                            seq)

                ;; === 2. Who isolation ===
                iso-checkpoint (get-max-line socket)

                _ (evaluate-code socket 2
                                 {:code "(+ 1 1)"
                                  :namespace "user"
                                  :session-key session-key
                                  :who "e2e-agent-alpha"
                                  :description "agent alpha eval"})

                _ (evaluate-code socket 3
                                 {:code "(+ 2 2)"
                                  :namespace "user"
                                  :session-key session-key
                                  :who "e2e-agent-beta"
                                  :description "agent beta eval"})

                alpha-rows (wait-for-output
                            socket
                            "[:find [(pull ?e [*]) ...] :in $ ?since ?who :where [?e :output/line ?l] [(> ?l ?since)] [?e :output/who ?who]]"
                            [iso-checkpoint "e2e-agent-alpha"]
                            seq)

                beta-rows (wait-for-output
                           socket
                           "[:find [(pull ?e [*]) ...] :in $ ?since ?who :where [?e :output/line ?l] [(> ?l ?since)] [?e :output/who ?who]]"
                           [iso-checkpoint "e2e-agent-beta"]
                           seq)

                ;; === 3. Who validation (reserved values) ===
                ui-result (evaluate-code socket 4
                                         {:code "(+ 1 1)"
                                          :namespace "user"
                                          :session-key session-key
                                          :who "ui"})

                api-result (evaluate-code socket 5
                                          {:code "(+ 1 1)"
                                           :namespace "user"
                                           :session-key session-key
                                           :who "api"})

                ;; === 4. Who validation (blank) ===
                blank-result (evaluate-code socket 6
                                            {:code "(+ 1 1)"
                                             :namespace "user"
                                             :session-key session-key
                                             :who ""})

                ;; === 5. Output categories per eval ===
                cat-checkpoint (get-max-line socket)

                _ (evaluate-code socket 7
                                 {:code "(* 3 7)"
                                  :namespace "user"
                                  :session-key session-key
                                  :who "e2e-category-test"
                                  :description "category pair test"})

                cat-rows (wait-for-output
                          socket
                          "[:find [(pull ?e [*]) ...] :in $ ?since ?who :where [?e :output/line ?l] [(> ?l ?since)] [?e :output/who ?who]]"
                          [cat-checkpoint "e2e-category-test"]
                          seq)

                ;; === 6. Stdout capture (evaluationOutput) ===
                stdout-checkpoint (get-max-line socket)

                _ (evaluate-code socket 8
                                 {:code "(println \"e2e-stdout-marker\")"
                                  :namespace "user"
                                  :session-key session-key
                                  :who "e2e-stdout-test"
                                  :description "stdout capture test"})

                stdout-rows (wait-for-output
                             socket
                             "[:find [(pull ?e [*]) ...] :in $ ?since ?who :where [?e :output/line ?l] [(> ?l ?since)] [?e :output/who ?who]]"
                             [stdout-checkpoint "e2e-stdout-test"]
                             seq)

                ;; === 7. Stderr capture (evaluationErrorOutput) ===
                stderr-checkpoint (get-max-line socket)

                _ (evaluate-code socket 9
                                 {:code "(binding [*out* *err*] (println \"e2e-stderr-marker\"))"
                                  :namespace "user"
                                  :session-key session-key
                                  :who "e2e-stderr-test"
                                  :description "stderr capture test"})

                stderr-rows (wait-for-output
                             socket
                             "[:find [(pull ?e [*]) ...] :in $ ?since ?who :where [?e :output/line ?l] [(> ?l ?since)] [?e :output/who ?who]]"
                             [stderr-checkpoint "e2e-stderr-test"]
                             seq)

                ;; === 8. otherWhosSinceLast ===
                _ (evaluate-code socket 10
                                 {:code "(+ 10 10)"
                                  :namespace "user"
                                  :session-key session-key
                                  :who "e2e-cross-a"
                                  :description "cross-who first"})

                _ (evaluate-code socket 11
                                 {:code "(+ 20 20)"
                                  :namespace "user"
                                  :session-key session-key
                                  :who "e2e-cross-b"
                                  :description "cross-who interloper"})

                cross-result (evaluate-code socket 12
                                            {:code "(+ 30 30)"
                                             :namespace "user"
                                             :session-key session-key
                                             :who "e2e-cross-a"
                                             :description "cross-who second"})

                ;; === 9. Category filter query ===
                cat-filter-checkpoint (get-max-line socket)

                _ (evaluate-code socket 13
                                 {:code "(+ 99 1)"
                                  :namespace "user"
                                  :session-key session-key
                                  :who "e2e-cat-filter"
                                  :description "category filter test"})

                results-only (wait-for-output
                              socket
                              "[:find [(pull ?e [*]) ...] :in $ ?since ?who :where [?e :output/line ?l] [(> ?l ?since)] [?e :output/who ?who] [?e :output/category \"evaluationResults\"]]"
                              [cat-filter-checkpoint "e2e-cat-filter"]
                              seq)

                ;; === 10. Aggregate query (count) ===
                count-result (query-output-log socket 14
                                               "[:find (count ?e) . :in $ ?who :where [?e :output/who ?who]]"
                                               ["e2e-cat-filter"])

                _ (mcp/stop-mcp-session! socket)]

          ;; --- Assertions ---

          (testing "Basic query: evaluating code produces queryable output"
            (is (seq basic-rows)
                "Should return at least one output row for the evaluation")
            (is (some #(= "evaluationResults" (:category %)) basic-rows)
                "Should include an evaluationResults entry")
            (is (every? #(> (:line %) checkpoint) basic-rows)
                "All returned rows should be after the checkpoint"))

          (testing "Who isolation: different who slugs return separate results"
            (is (every? #(= "e2e-agent-alpha" (:who %)) alpha-rows)
                "Alpha rows should only contain alpha's output")
            (is (every? #(= "e2e-agent-beta" (:who %)) beta-rows)
                "Beta rows should only contain beta's output")
            (is (not= (set (map :line alpha-rows))
                      (set (map :line beta-rows)))
                "Alpha and beta should have different line numbers"))

          (testing "Who validation: reserved values rejected"
            (is (some? (:error ui-result))
                "who='ui' should return an error")
            (is (some? (:error api-result))
                "who='api' should return an error"))

          (testing "Who validation: blank value rejected"
            (is (some? (:error blank-result))
                "who='' (blank) should return an error"))

          (testing "Output categories: eval produces evaluationResults with who"
            (is (some #(= "evaluationResults" (:category %)) cat-rows)
                "Should have an evaluationResults entry"))

          (testing "Stdout capture: println produces evaluationOutput"
            (is (some #(= "evaluationOutput" (:category %)) stdout-rows)
                "Should have an evaluationOutput entry for stdout"))

          (testing "Stderr capture: writing to *err* produces output"
            (is (some #(or (= "evaluationErrorOutput" (:category %))
                           (= "evaluationOutput" (:category %)))
                      stderr-rows)
                "Should have error output or output entry for stderr"))

          (testing "otherWhosSinceLast: cross-who awareness"
            (is (some? (:other-whos-since-last cross-result))
                "Should include other-whos-since-last field")
            (is (some #(= "e2e-cross-b" %) (:other-whos-since-last cross-result))
                "Should list the interloping who slug"))

          (testing "Category filter query: can filter by specific category"
            (is (every? #(= "evaluationResults" (:category %)) results-only)
                "Results-only query should return only evaluationResults"))

          (testing "Aggregate query: count returns a scalar"
            (is (number? count-result)
                "Count query should return a number")
            (is (pos? count-result)
                "Count should be positive for a who with output")))

        (p/catch (fn [e]
                   (js/console.error "[output-log] Error:" (.-message e) e)
                   (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                   (throw e)))
        (p/finally (fn []
                     (mcp/restore-settings! backup-path))))))

(deftest-async image-content-in-output-log
  (testing "Output log query with image data returns image content items"
    (let [backup-path (mcp/backup-settings! "output-log-image-test-backup.json")]
      (-> (p/let [_ (mcp/ensure-repl-and-eval-enabled!)
                  {:keys [socket]} (mcp/start-mcp-session!)
                  session-key (get-session-key socket)
                  checkpoint (get-max-line socket)

                  ;; Evaluate code that returns a data URL
                  _ (evaluate-code socket 20
                                   {:code "\"data:image/png;base64,iVBORw0KGgo=\""
                                    :namespace "user"
                                    :session-key session-key
                                    :who "e2e-output-image"
                                    :description "output log image test"})

                  ;; Wait for the eval result to appear in the log
                  _ (wait-for-output
                     socket
                     "[:find [(pull ?e [*]) ...] :in $ ?since ?who :where [?e :output/line ?l] [(> ?l ?since)] [?e :output/who ?who] [?e :output/category \"evaluationResults\"]]"
                     [checkpoint "e2e-output-image"]
                     seq)

                  ;; Query via send-request to see all content items (including images)
                  log-resp (mcp/send-request socket
                                             {:jsonrpc "2.0"
                                              :id 21
                                              :method "tools/call"
                                              :params {:name "clojure_repl_output_log"
                                                       :arguments {:query "[:find [(pull ?e [*]) ...] :in $ ?since ?who :where [?e :output/line ?l] [(> ?l ?since)] [?e :output/who ?who] [?e :output/category \"evaluationResults\"]]"
                                                                   :inputs [checkpoint "e2e-output-image"]
                                                                   :maxImages 1}}})
                  content (get-in (js->clj log-resp :keywordize-keys true)
                                  [:result :content])

                  _ (mcp/stop-mcp-session! socket)]

            (is (= 2 (count content))
                "Should have text + image content items")
            (is (= "text" (:type (first content)))
                "First content item should be text")
            (is (= "image" (:type (second content)))
                "Second content item should be image")
            (is (= "image/png" (:mimeType (second content)))
                "Image content should have correct MIME type")
            (is (string? (:data (second content)))
                "Image content should have base64 data string"))
          (p/catch (fn [e]
                     (js/console.error "[output-log-image] Error:" (.-message e) e)
                     (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                     (throw e)))
          (p/finally (fn []
                       (mcp/restore-settings! backup-path)))))))

(deftest-async output-log-default-caps-images
  (testing "Output log query with image data returns only text by default (maxImages defaults to 0)"
    (let [backup-path (mcp/backup-settings! "output-log-default-cap-backup.json")]
      (-> (p/let [_ (mcp/ensure-repl-and-eval-enabled!)
                  {:keys [socket]} (mcp/start-mcp-session!)
                  session-key (get-session-key socket)
                  checkpoint (get-max-line socket)

                  ;; Evaluate code that returns a data URL
                  _ (evaluate-code socket 30
                                   {:code "\"data:image/png;base64,iVBORw0KGgo=\""
                                    :namespace "user"
                                    :session-key session-key
                                    :who "e2e-output-cap"
                                    :description "output log default cap test"})

                  ;; Wait for the eval result to appear in the log
                  _ (wait-for-output
                     socket
                     "[:find [(pull ?e [*]) ...] :in $ ?since ?who :where [?e :output/line ?l] [(> ?l ?since)] [?e :output/who ?who] [?e :output/category \"evaluationResults\"]]"
                     [checkpoint "e2e-output-cap"]
                     seq)

                  ;; Query without maxImages — default is 0, so no images
                  log-resp (mcp/send-request socket
                             {:jsonrpc "2.0"
                              :id 31
                              :method "tools/call"
                              :params {:name "clojure_repl_output_log"
                                       :arguments {:query "[:find [(pull ?e [*]) ...] :in $ ?since ?who :where [?e :output/line ?l] [(> ?l ?since)] [?e :output/who ?who] [?e :output/category \"evaluationResults\"]]"
                                                   :inputs [checkpoint "e2e-output-cap"]}}})
                  content (get-in (js->clj log-resp :keywordize-keys true)
                                  [:result :content])

                  _ (mcp/stop-mcp-session! socket)]
            (is (= 1 (count content))
                "Should have only text content (no images by default)")
            (is (= "text" (:type (first content)))
                "Content should be text type")
            (is (string? (re-find #"image-1-capped" (:text (first content))))
                "Text should contain capped image marker"))
          (p/catch (fn [e]
                     (js/console.error "[output-log-cap] Error:" (.-message e) e)
                     (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                     (throw e)))
          (p/finally (fn []
                       (mcp/restore-settings! backup-path)))))))

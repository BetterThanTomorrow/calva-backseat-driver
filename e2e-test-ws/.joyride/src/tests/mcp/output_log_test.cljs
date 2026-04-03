(ns tests.mcp.output-log-test
  (:require
   ["vscode" :as vscode]
   [cljs.test :refer [deftest is testing]]
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
  [socket query inputs pred & {:keys [timeout] :or {timeout 5000}}]
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

                ;; === Basic query ===
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

                _ (mcp/stop-mcp-session! socket)]

          ;; --- Assertions ---

          (testing "Basic query: evaluating code produces queryable output"
            (is (seq basic-rows)
                "Should have output entries after evaluation")
            (is (some #(= "evaluationResults" (:category %)) basic-rows)
                "Should include an evaluationResults entry")
            (is (every? #(> (:line %) checkpoint) basic-rows)
                "All entries should be after checkpoint")))

        (p/catch (fn [e]
                   (js/console.error "[output-log] Error:" (.-message e) e)
                   (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                   (throw e)))
        (p/finally (fn []
                     (mcp/restore-settings! backup-path))))))

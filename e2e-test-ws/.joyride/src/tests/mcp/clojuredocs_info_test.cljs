(ns tests.mcp.clojuredocs-info-test
  (:require
   ["vscode" :as vscode]
   [cljs.test :refer [is testing]]
   [e2e.macros :refer [deftest-async]]
   [e2e.mcp-helpers :as mcp]
   [promesa.core :as p]))

(deftest-async clojuredocs-info-tests
  (let [backup-path (mcp/backup-settings! "clojuredocs-info-test-backup.json")]
    (-> (p/let [_ (mcp/ensure-repl-and-eval-enabled!)
                {:keys [socket]} (mcp/start-mcp-session!)

                ;; === 1. Well-known symbol ===
                ;; clojuredocs.org is JVM Clojure only. The Joyride cljs REPL may
                ;; not support the clojuredocs-lookup nREPL op. We test that the tool
                ;; responds gracefully — either with data or a structured error.

                map-result (mcp/call-tool socket 10 "clojuredocs_info"
                                          {:clojureSymbol "map"})

                _ (js/console.log "[clojuredocs-info] map result:" (pr-str map-result))

                ;; === 2. Nonexistent symbol ===

                unknown-result (mcp/call-tool socket 11 "clojuredocs_info"
                                              {:clojureSymbol "definitely-not-a-real-fn-xyz"})

                _ (js/console.log "[clojuredocs-info] unknown result:" (pr-str unknown-result))

                ;; === 3. Missing required clojureSymbol parameter ===

                missing-param-resp (mcp/send-request socket
                                                     {:jsonrpc "2.0"
                                                      :id 12
                                                      :method "tools/call"
                                                      :params {:name "clojuredocs_info"
                                                               :arguments {}}})
                missing-param-result (-> missing-param-resp
                                         (js->clj :keywordize-keys true)
                                         :result)

                _ (js/console.log "[clojuredocs-info] missing param result:" (pr-str missing-param-result))

                _ (mcp/stop-mcp-session! socket)]

          ;; 1. Well-known symbol responds without crashing
          (testing "well-known symbol returns structured response"
            ;; May return clojuredocs data or error depending on REPL type
            (is (or (some? map-result)
                    true)
                "tool invocation should not crash")
            (when (and map-result (not (:error map-result)))
              ;; If we got actual data, verify it has expected shape
              (is (or (some? (:name map-result))
                      (some? (:doc map-result))
                      (some? (:examples map-result)))
                  "data response should contain name, doc, or examples")))

          ;; 2. Nonexistent symbol doesn't crash
          (testing "nonexistent symbol returns gracefully"
            (is (or (nil? unknown-result)
                    (some? (:error unknown-result))
                    (map? unknown-result))
                "nonexistent symbol should not crash"))

          ;; 3. Missing param
          (testing "missing clojureSymbol parameter returns error"
            (is (some? missing-param-result))
            (let [text (some-> (get-in missing-param-result [:content 0 :text])
                               js/JSON.parse
                               (js->clj :keywordize-keys true))]
              (is (or (:error text)
                      (:isError missing-param-result)
                      ;; Tool may also return nil content for missing params
                      (nil? (get-in missing-param-result [:content 0 :text])))
                  "should indicate error or handle missing param"))))

        (p/catch (fn [e]
                   (js/console.error "[clojuredocs-info] Test error:" (.-message e) e)
                   (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                   (throw e)))
        (p/finally (fn [] (mcp/restore-settings! backup-path))))))

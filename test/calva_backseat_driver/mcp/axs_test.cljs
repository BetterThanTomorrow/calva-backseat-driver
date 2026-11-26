(ns calva-backseat-driver.mcp.axs-test
  (:require [cljs.test :refer [deftest testing is]]
            [calva-backseat-driver.mcp.axs :as mcp-axs]))

(deftest sync-cursor-mcp-when-context-keys-test
  (testing "when-context keys match package.json enablement strings"
    (is (= ":calva-backseat-driver/mcp-server-registered-with-cursor?"
           (str :calva-backseat-driver/mcp-server-registered-with-cursor?)))
    (is (= ":calva-backseat-driver/cursor-mcp-available?"
           (str :calva-backseat-driver/cursor-mcp-available?)))))

(deftest update-registry-action-test
  (testing "routes to update-registry effect with wrapper path"
    (let [result (mcp-axs/handle-action {:mcp/wrapper-config-path "/tmp/w"}
                                        nil
                                        [:mcp/ax.update-registry])]
      (is (= [[:mcp/fx.update-registry {:mcp/wrapper-config-path "/tmp/w"}]]
             (:ex/fxs result))))))

(deftest cursor-registration-is-logged
  (let [state {:app/min-log-level :debug
               :app/log-file-uri :the-log-file}
        log-options {:app/min-log-level :debug
                     :app/log-file-uri :the-log-file}]
    (testing "a registration writes a log line"
      (let [result (mcp-axs/handle-action state nil [:mcp/ax.cursor-mcp-registered {:ok true}])]
        (is (= [[:app/fx.log log-options :info ["Cursor MCP server registered:" {:ok true}]]]
               (:ex/fxs result)))
        (is (= [[:mcp/ax.sync-cursor-mcp-when-contexts]]
               (:ex/dxs result)))))
    (testing "a failed registration writes a log line"
      (let [result (mcp-axs/handle-action state nil [:mcp/ax.cursor-mcp-registration-failed {:ok false}])]
        (is (= [[:app/fx.log log-options :warn ["Cursor MCP auto-registration failed:" {:ok false}]]]
               (:ex/fxs result)))
        (is (= [[:mcp/ax.sync-cursor-mcp-when-contexts]]
               (:ex/dxs result)))))))

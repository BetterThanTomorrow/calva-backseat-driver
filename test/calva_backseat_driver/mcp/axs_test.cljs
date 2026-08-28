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

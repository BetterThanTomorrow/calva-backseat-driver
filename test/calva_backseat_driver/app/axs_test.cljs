(ns calva-backseat-driver.app.axs-test
  (:require [cljs.test :refer [deftest testing is]]
            [calva-backseat-driver.app.axs :as app-axs]))

(deftest app-init-action-test
  (testing "app/ax.init matches map payload (not {:keys ...} — cljs.core.match limitation)"
    (let [state {:mcp/cursor-mcp-available? true}
          result (app-axs/handle-action state {}
                                        [:app/ax.init {:auto-start-mcp? false
                                                       :auto-register-cursor-mcp? true}])]
      (is (some? result) "handler must not fall through to :else nil")
      (is (vector? (:ex/dxs result)))
      (is (some #(= :calva-mcp-extension/activated? (second %))
                (:ex/dxs result))
          "sets activated context")
      (is (some #(= :mcp/ax.sync-cursor-mcp-when-contexts (first %))
                (:ex/dxs result))
          "syncs Cursor MCP when-contexts on init")))

  (testing "auto-start when Cursor auto-register enabled"
    (let [state {:mcp/cursor-mcp-available? true}
          result (app-axs/handle-action state {}
                                        [:app/ax.init {:auto-start-mcp? false
                                                       :auto-register-cursor-mcp? true}])]
      (is (some #(and (vector? %) (= :mcp/ax.start-server (first %)))
                (:ex/dxs result))
          "dispatches silent MCP start"))))

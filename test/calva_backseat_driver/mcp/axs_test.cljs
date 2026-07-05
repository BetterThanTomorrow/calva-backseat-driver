(ns calva-backseat-driver.mcp.axs-test
  (:require [cljs.test :refer [deftest testing is]]))

(deftest sync-cursor-mcp-when-context-keys-test
  (testing "when-context keys match package.json enablement strings"
    (is (= ":calva-backseat-driver/cursor-mcp-registered?"
           (str :calva-backseat-driver/cursor-mcp-registered?)))
    (is (= ":calva-backseat-driver/cursor-mcp-available?"
           (str :calva-backseat-driver/cursor-mcp-available?)))))

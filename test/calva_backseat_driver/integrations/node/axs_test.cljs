(ns calva-backseat-driver.integrations.node.axs-test
  (:require [cljs.test :refer [deftest is]]
            [calva-backseat-driver.integrations.node.axs :as node-axs]))

(deftest unknown-node-action
  (is (= {:ex/fxs [[:node/fx.log-error "Unknown action:" "[:node/ax.nope 1]"]]}
         (node-axs/handle-action {} nil [:node/ax.nope 1]))
      "An unknown action returns the effect that logs the error"))

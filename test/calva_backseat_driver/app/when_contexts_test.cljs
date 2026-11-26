(ns calva-backseat-driver.app.when-contexts-test
  (:require ["fs" :as fs]
            ["path" :as path]
            [cljs.test :refer [deftest testing is]]
            [calva-backseat-driver.app.when-contexts :as when-contexts]))

(def ^:private all-off
  {:calva-mcp-extension/activated? false
   :calva-backseat-driver/started? false
   :calva-backseat-driver/cursor-mcp-available? false
   :calva-backseat-driver/mcp-server-registered-with-cursor? false})

(deftest when-contexts
  (is (= all-off (when-contexts/when-contexts {}))
      "Before activation every context is off")
  (is (= (assoc all-off :calva-mcp-extension/activated? true)
         (when-contexts/when-contexts {:app/activated? true}))
      "An activated extension with no server")
  (is (= (assoc all-off :calva-mcp-extension/activated? true)
         (when-contexts/when-contexts {:app/activated? true
                                       :server/starting? true}))
      "A server that is starting, before it listens, isn't started")
  (is (= (assoc all-off
                :calva-mcp-extension/activated? true
                :calva-backseat-driver/started? true)
         (when-contexts/when-contexts {:app/activated? true
                                       :server/starting? true
                                       :server/running? true}))
      "A start that is finishing its registrations is started already")
  (is (= (assoc all-off
                :calva-mcp-extension/activated? true
                :calva-backseat-driver/started? true)
         (when-contexts/when-contexts {:app/activated? true
                                       :server/running? true}))
      "A running server")
  (is (= (assoc all-off
                :calva-mcp-extension/activated? true
                :calva-backseat-driver/started? true)
         (when-contexts/when-contexts {:app/activated? true
                                       :server/running? true
                                       :server/stopping? true}))
      "A server that is stopping is started until it has closed")
  (testing "in Cursor"
    (is (= (assoc all-off
                  :calva-mcp-extension/activated? true
                  :calva-backseat-driver/cursor-mcp-available? true)
           (when-contexts/when-contexts {:app/activated? true
                                         :mcp/cursor-mcp-available? true}))
        "Cursor's registration API is available")
    (is (= (assoc all-off
                  :calva-mcp-extension/activated? true
                  :calva-backseat-driver/cursor-mcp-available? true
                  :calva-backseat-driver/started? true
                  :calva-backseat-driver/mcp-server-registered-with-cursor? true)
           (when-contexts/when-contexts {:app/activated? true
                                         :mcp/cursor-mcp-available? true
                                         :server/running? true
                                         :lifecycle/registered-name "calva-backseat-driver-ws-abc-0"}))
        "A running server that is registered")
    (is (false? (:calva-backseat-driver/mcp-server-registered-with-cursor?
                 (when-contexts/when-contexts {:app/activated? true
                                               :mcp/cursor-mcp-available? true
                                               :lifecycle/registered-name "calva-backseat-driver-ws-abc-0"})))
        "It is registered only while the server runs")))

(deftest the-contexts-the-manifest-names
  (let [manifest (fs/readFileSync (path/resolve "package.json") "utf8")
        named (set (re-seq #":calva-[a-z-]+/[a-z-]+\?" manifest))]
    (is (= #{":calva-mcp-extension/activated?"
             ":calva-backseat-driver/started?"
             ":calva-backseat-driver/cursor-mcp-available?"
             ":calva-backseat-driver/mcp-server-registered-with-cursor?"}
           named)
        "package.json names these contexts")
    (is (= named (set (map str (keys (when-contexts/when-contexts {})))))
        "and they are the contexts that are computed from the state")))

(deftest effects
  (is (nil? (when-contexts/effects {:app/activated? true :calva/output-line-counter 1}
                                   {:app/activated? true :calva/output-line-counter 2}))
      "A change that leaves the contexts as they were calls for nothing")
  (is (= [[:vscode/fx.set-contexts (assoc all-off
                                          :calva-mcp-extension/activated? true
                                          :calva-backseat-driver/started? true)]]
         (when-contexts/effects {:app/activated? true}
                                {:app/activated? true :server/running? true}))
      "A change that gives a context another value sets the contexts"))

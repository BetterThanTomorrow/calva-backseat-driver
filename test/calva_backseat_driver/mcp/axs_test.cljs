(ns calva-backseat-driver.mcp.axs-test
  (:require [cljs.test :refer [deftest testing is]]
            [calva-backseat-driver.event-handler :as event-handler]
            [calva-backseat-driver.mcp.axs :as mcp-axs]))

(defn- handle
  "Returns what the MCP handlers give for `action` on `state`."
  [state action]
  (event-handler/handle-actions mcp-axs/actions state {:system/now 0} [action]))

(def ^:private state
  {:mcp/wrapper-config-path "/tmp/w"
   :vscode/config.mcpHost "127.0.0.1"
   :vscode/config.mcpSocketServerPort 1664
   :app/min-log-level :debug
   :app/log-file-uri "file:///logs/mcp-server.log"
   :calva/sessions [{:replSessionKey "clj"
                     :replType "clj"
                     :projectRoot "/projects/mini"
                     :globs ["**/*.clj"]
                     :supportsRuntimes false
                     :lastActivity 1795517607133}]})

(def ^:private lifecycle-options
  {:mcp/wrapper-config-path "/tmp/w"
   :mcp/settings {:vscode/config.mcpHost "127.0.0.1"
                  :vscode/config.mcpSocketServerPort 1664}
   :mcp/registry-data {:sessions [{:replSessionKey "clj"
                                   :projectRoot "/projects/mini"
                                   :globs ["**/*.clj"]
                                   :supportsRuntimes false}]}})

(def ^:private log-options
  {:app/min-log-level :debug
   :app/log-file-uri "file:///logs/mcp-server.log"})

(def ^:private stopped
  "The server's state, as vscode-mcp reports it, for a server that doesn't run."
  {:server/running? false
   :server/starting? false
   :server/stopping? false
   :server/host nil
   :server/assigned-port nil
   :server/instance-slug nil
   :server/port-file-path nil
   :server/error nil
   :lifecycle/registered-name nil
   :lifecycle/registration-error nil})

(def ^:private running
  (assoc stopped
         :server/running? true
         :server/host "127.0.0.1"
         :server/assigned-port 49311
         :server/instance-slug "ws-bxnohh"
         :server/port-file-path "/home/.config/vscode-mcp/port-files/calva-backseat-driver-ws-bxnohh.port"))

(def ^:private registered
  (assoc running :lifecycle/registered-name "calva-backseat-driver-ws-bxnohh-0"))

(defn- registration-failed
  "The state of `server` after a Cursor registration failed with `registration-error`."
  [server registration-error]
  (assoc server :lifecycle/registration-error registration-error))

(deftest server-changed
  (testing "a change of the server's state"
    (let [result (handle state [:mcp/ax.server-changed (assoc stopped :server/starting? true)])]
      (is (= (merge state stopped {:server/starting? true})
             (:uf/db result))
          "The state takes the server's state")
      (is (empty? (:uf/fxs result))
          "and nothing else happens")))
  (testing "a start that failed"
    (let [failed (assoc stopped :server/error "listen EADDRINUSE: address already in use")
          result (handle (merge state stopped) [:mcp/ax.server-changed failed])]
      (is (= [[:node/fx.log-error "MCP server error:" "listen EADDRINUSE: address already in use"]
              [:vscode/fx.show-error-message "MCP server error: listen EADDRINUSE: address already in use"]]
             (:uf/fxs result))
          "The person is shown the error, and it goes to the console")
      (is (empty? (:uf/fxs (handle (:uf/db result) [:mcp/ax.server-changed (assoc failed :server/stopping? true)])))
          "An error that the state has already isn't shown again")))
  (testing "a registration with Cursor"
    (is (= [[:app/fx.log log-options :info ["Cursor MCP server registered:" "calva-backseat-driver-ws-bxnohh-0"]]]
           (:uf/fxs (handle (merge state running) [:mcp/ax.server-changed registered])))
        "is logged"))
  (testing "a registration with Cursor that failed"
    (is (= [[:app/fx.log log-options :warn ["Cursor MCP auto-registration failed:" {:reason :port-file-not-ready}]]]
           (:uf/fxs (handle (merge state running)
                            [:mcp/ax.server-changed (registration-failed running {:reason :port-file-not-ready})])))
        "is logged as a warning")))

(deftest start-server
  (testing "started by a person"
    (let [result (handle state [:mcp/ax.start-server])]
      (is (= [[:uf/await :mcp/fx.lifecycle-start (assoc lifecycle-options :lifecycle/silent? nil)]]
             (:uf/fxs result))
          "The start gets the wrapper's directory, the MCP settings and Calva's sessions for the registry entry")
      (is (= [[:mcp/ax.lifecycle-updated :uf/prev-result]]
             (:uf/dxs result))
          "and the state takes the server's state that the start resolves to")))
  (testing "started by the extension"
    (is (= [[:uf/await :mcp/fx.lifecycle-start (assoc lifecycle-options :lifecycle/silent? true)]]
           (:uf/fxs (handle state [:mcp/ax.start-server {:silent? true}])))
        "The start is silent")))

(deftest lifecycle-updated
  (testing "a server that runs"
    (let [result (handle state [:mcp/ax.lifecycle-updated running])]
      (is (= (merge state running) (:uf/db result))
          "The state takes the server's state")
      (is (= [[:uf/await :app/fx.return running]]
             (:uf/fxs result))
          "The start command resolves to the server's state, with its port and host")
      (is (= [[:mcp/ax.update-registry]]
             (:uf/dxs result))
          "The registry entry gets the sessions as they are after the start")))
  (testing "no server after a start"
    (let [result (handle state [:mcp/ax.lifecycle-updated stopped])]
      (is (= [[:uf/await :app/fx.return nil]]
             (:uf/fxs result))
          "The start command resolves to nil")
      (is (empty? (:uf/dxs result))))))

(deftest stop-server
  (let [result (handle state [:mcp/ax.stop-server {:lifecycle/silent? true}])]
    (is (= [[:uf/await :mcp/fx.lifecycle-stop (assoc lifecycle-options :lifecycle/silent? true)]]
           (:uf/fxs result)))
    (is (= [[:mcp/ax.lifecycle-stopped :uf/prev-result]]
           (:uf/dxs result)))))

(deftest lifecycle-stopped
  (let [result (handle (merge state running) [:mcp/ax.lifecycle-stopped stopped])]
    (is (= (merge state stopped) (:uf/db result))
        "The state takes the server's state")
    (is (= [[:uf/await :app/fx.return true]]
           (:uf/fxs result))
        "The stop command resolves to true")))

(deftest update-registry
  (is (= [[:mcp/fx.update-registry lifecycle-options]]
         (:uf/fxs (handle state [:mcp/ax.update-registry])))
      "The registry entry gets Calva's sessions, with the fields an entry has for a session"))

(deftest register-with-cursor
  (let [result (handle state [:mcp/ax.register-with-cursor])]
    (is (= [[:uf/await :mcp/fx.register-with-cursor lifecycle-options]]
           (:uf/fxs result)))
    (is (= [[:mcp/ax.cursor-registration-finished :uf/prev-result]]
           (:uf/dxs result)))))

(deftest cursor-registration-finished
  (testing "a registration"
    (let [result (handle state [:mcp/ax.cursor-registration-finished registered])]
      (is (= [[:mcp/ax.lifecycle-updated registered]]
             (:uf/dxs result))
          "The command resolves as a start does")
      (is (empty? (:uf/fxs result)))))
  (testing "outside Cursor"
    (let [failed (registration-failed stopped {:reason :cursor-api-unavailable})
          result (handle state [:mcp/ax.cursor-registration-finished failed])]
      (is (= [[:vscode/fx.show-information-message "Cursor MCP registration API is not available in this editor."]]
             (:uf/fxs result))
          "The person is told that the editor has no registration API")))
  (testing "a registration that didn't take"
    (let [failed (registration-failed running {:reason :registration-failed})
          result (handle state [:mcp/ax.cursor-registration-finished failed])]
      (is (= [[:vscode/fx.show-information-message "Could not register Backseat Driver MCP server with Cursor."]]
             (:uf/fxs result))
          "The person is told that it could not register")
      (is (= (merge state failed) (:uf/db result))
          "The state has the server that the registration started")))
  (testing "a registration that threw"
    (let [failed (registration-failed running {:reason :registration-failed :message "boom"})]
      (is (= [[:vscode/fx.show-information-message "Could not register Backseat Driver MCP server with Cursor."]]
             (:uf/fxs (handle state [:mcp/ax.cursor-registration-finished failed])))
          "The person is told the same"))))

(deftest open-server-log
  (is (= [[:uf/await :vscode/fx.show-text-document-at "file:///logs/mcp-server.log"]]
         (:uf/fxs (handle state [:mcp/ax.open-server-log])))
      "The command resolves when the log file is shown"))

(deftest handle-request
  (let [request {:jsonrpc "2.0" :id 1 :method "tools/list"}]
    (is (= [[:uf/await :mcp/fx.handle-request
             {:mcp/manifest {:manifest/name "calva-backseat-driver"}
              :mcp/repl-enabled? false
              :mcp/when-clause-settings {"config.calva-backseat-driver.enableMcpReplEvaluation" false
                                         ":calva-mcp-extension/activated?" true}}
             request]]
           (:uf/fxs (handle (assoc state
                                   :vscode/config.enableMcpReplEvaluation false
                                   :extension/manifest {:manifest/name "calva-backseat-driver"})
                            [:mcp/ax.handle-request request])))
        "The request is answered from the manifest, with the settings that gate tools and skills")))

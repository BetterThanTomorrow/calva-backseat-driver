(ns calva-backseat-driver.mcp.cursor-registration
  (:require
   [calva-backseat-driver.integrations.vscode.cursor-config :as cursor-config]))

(defn read-auto-register-cursor-mcp? [state]
  (let [v (some-> ^js ((:app/getConfiguration state) "calva-backseat-driver")
                  (.get "autoRegisterCursorMcp"))]
    (if (nil? v) true v)))

(defn should-register-on-server-started? [state server-info]
  (cursor-config/should-register-cursor-mcp?
   (read-auto-register-cursor-mcp? state)
   (:mcp/cursor-mcp-available? state)
   server-info))

(defn server-started-fxs
  [server-info silent? wrapper-config-path register?]
  (into []
        (cond-> []
          (not silent?)
          (conj [:mcp/fx.show-server-started-message server-info wrapper-config-path])
          register?
          (conj [:mcp/fx.register-cursor-mcp-server
                 server-info
                 {:ex/on-success [[:mcp/ax.cursor-mcp-registered :ex/action-args]]
                  :ex/on-error [[:mcp/ax.cursor-mcp-registration-failed :ex/action-args]]}])
          :always
          (conj [:app/fx.return (clj->js server-info)]))))

(ns calva-backseat-driver.app.when-contexts
  "The when-contexts that the manifest's commands and tools are offered by, as a
   function of the app state.")

(defn when-contexts
  "Returns the when-contexts for `state`, by context key."
  [{:app/keys [activated?]
    :mcp/keys [cursor-mcp-available?]
    :server/keys [running? starting? stopping?]
    :lifecycle/keys [registered-name]}]
  {:calva-mcp-extension/activated? (boolean activated?)
   :calva-backseat-driver/started? (boolean running?)
   :calva-backseat-driver/starting? (boolean starting?)
   :calva-backseat-driver/stopping? (boolean stopping?)
   :calva-backseat-driver/cursor-mcp-available? (boolean cursor-mcp-available?)
   :calva-backseat-driver/mcp-server-registered-with-cursor? (boolean (and running?
                                                                           registered-name))})

(defn effects
  "Returns the effects that a change from `old-state` to `new-state` calls for: the
   when-contexts are set when the change gave them other values."
  [old-state new-state]
  (let [contexts (when-contexts new-state)]
    (when (not= contexts (when-contexts old-state))
      [[:vscode/fx.set-contexts contexts]])))

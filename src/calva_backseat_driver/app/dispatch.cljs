(ns calva-backseat-driver.app.dispatch
  "The extension's loop: its state, its action tables and its effect tables."
  (:require
   [calva-backseat-driver.app.axs :as app-axs]
   [calva-backseat-driver.app.db :as db]
   [calva-backseat-driver.app.fxs :as app-fxs]
   [calva-backseat-driver.app.when-contexts :as when-contexts]
   [calva-backseat-driver.event-handler :as event-handler]
   [calva-backseat-driver.integrations.calva.axs :as calva-axs]
   [calva-backseat-driver.integrations.calva.fxs :as calva-fxs]
   [calva-backseat-driver.integrations.node.fxs :as node-fxs]
   [calva-backseat-driver.integrations.vscode.fxs :as vscode-fxs]
   [calva-backseat-driver.mcp.axs :as mcp-axs]
   [calva-backseat-driver.mcp.fxs :as mcp-fxs]
   [calva-backseat-driver.tools.axs :as tools-axs]
   [calva-backseat-driver.tools.fxs :as tools-fxs]))

(defn actions
  "Returns the action table: everything the extension can decide."
  []
  (event-handler/merge-tables [app-axs/actions
                               calva-axs/actions
                               mcp-axs/actions
                               tools-axs/actions]))

(defn effects
  "Returns the effect table: everything the extension can do."
  []
  (event-handler/merge-tables [app-fxs/effects
                               calva-fxs/effects
                               mcp-fxs/effects
                               node-fxs/effects
                               tools-fxs/effects
                               vscode-fxs/effects]))

(def ^:private dispatch-log-size 200)

(defonce ^{:doc "The dispatch log: the last dispatches of a development build, newest last."}
  !dispatch-log
  (atom []))

(defn- log-dispatch! [entry]
  (swap! !dispatch-log
         (fn [dispatch-log]
           (let [dispatch-log (conj dispatch-log entry)]
             (cond-> dispatch-log
               (< dispatch-log-size (count dispatch-log)) (subvec 1))))))

(defn dispatch!
  "Dispatches `action-vectors` through the extension's loop. Returns a promise of the
   chain's last awaited result."
  [action-vectors]
  (event-handler/dispatch!+ {:uf/!state db/!app-db
                             :uf/action-table (actions)
                             :uf/effect-table (effects)
                             :uf/dispatch! dispatch!
                             :uf/state-effects when-contexts/effects
                             :uf/log! (when js/goog.DEBUG log-dispatch!)}
                            action-vectors))

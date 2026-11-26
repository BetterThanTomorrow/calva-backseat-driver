(ns calva-backseat-driver.app.fxs
  (:require
   ["vscode" :as vscode]
   [calva-backseat-driver.app.live-objects :as live-objects]
   [calva-backseat-driver.mcp.logging :as logging]
   [calva-backseat-driver.tools :as tools]))

(def ^:private log-levels {:error 0, :warn 1, :info 2, :debug 3})

(defn- log
  "Writes `messages` to the log file when `level` is at or above the minimum level in `options`."
  [_dispatch! options level messages]
  (when (<= (log-levels level) (log-levels (:app/min-log-level options :debug)))
    (apply logging/log! options level messages)))

(defn- return
  "Returns `v`. Awaited, it makes `v` the result of the dispatch."
  [_dispatch! v]
  v)

(defn- dispatch
  "Dispatches `actions` as a chain of their own."
  [dispatch! actions]
  (dispatch! actions)
  nil)

(defn- register-command
  "Registers the command `command-id`. Running it dispatches `actions`, and the command
   resolves to the result of that dispatch."
  [dispatch! command-id actions]
  (live-objects/add-disposables!
   [(vscode/commands.registerCommand command-id
                                     (fn [& _args]
                                       (dispatch! actions)))]))

(defn- register-language-model-tools
  "Registers the tools with the Language Model API."
  [dispatch!]
  (live-objects/add-disposables!
   (tools/register-language-model-tools dispatch!)))

(defn- clear-disposables
  "Disposes what the extension has registered."
  [_dispatch!]
  (live-objects/dispose-all!))

(def effects
  {:app/fx.log #'log
   :app/fx.return #'return
   :app/fx.register-command #'register-command
   :app/fx.register-language-model-tools #'register-language-model-tools
   :app/fx.clear-disposables #'clear-disposables
   :uf/fx.dispatch #'dispatch})

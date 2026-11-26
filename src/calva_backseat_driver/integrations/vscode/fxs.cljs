(ns calva-backseat-driver.integrations.vscode.fxs
  (:require
   ["vscode" :as vscode]
   [calva-backseat-driver.app.live-objects :as live-objects]
   [calva-backseat-driver.integrations.vscode.settings :as settings]))

(defn- show-information-message
  "Shows `message` as an information message."
  [_dispatch! message]
  (vscode/window.showInformationMessage message))

(defn- show-error-message
  "Shows `message` as an error message."
  [_dispatch! message]
  (vscode/window.showErrorMessage message))

(defn- show-text-document-at
  "Opens the document at `uri` in an editor."
  [_dispatch! uri]
  (-> (vscode/workspace.openTextDocument uri)
      (.then (fn [document]
               (when document
                 (vscode/window.showTextDocument document)))
             (fn [error]
               (js/console.error "Error opening or showing document:" uri error)))))

(defn- set-context
  "Sets the when-context `k` to `v`."
  [_dispatch! k v]
  (vscode/commands.executeCommand "setContext" (str k) v))

(defn- subscribe-to-settings
  "Dispatches `[:app/ax.settings-changed settings]` whenever a setting of the extension changes."
  [dispatch!]
  (live-objects/add-disposables!
   [(vscode/workspace.onDidChangeConfiguration
     (fn [^js event]
       (when (.affectsConfiguration event settings/section)
         (dispatch! [[:app/ax.settings-changed (settings/settings (live-objects/extension-context))]]))))]))

(def effects
  {:vscode/fx.show-information-message #'show-information-message
   :vscode/fx.show-error-message #'show-error-message
   :vscode/fx.show-text-document-at #'show-text-document-at
   :vscode/fx.set-context #'set-context
   :vscode/fx.subscribe-to-settings #'subscribe-to-settings})

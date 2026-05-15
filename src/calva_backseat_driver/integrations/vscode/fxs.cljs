(ns calva-backseat-driver.integrations.vscode.fxs
  (:require
   ["vscode" :as vscode]
   [calva-backseat-driver.ex.ax :as ax]
   [clojure.core.match :refer [match]]))

(defn- show-input-box! [dispatch! context options]
  (let [{:ex/keys [then]} options]
    (if-not then
      (vscode/window.showInputBox options)
      (-> (vscode/window.showInputBox (clj->js options))
          (.then (fn [input]
                   (when input
                     (dispatch! context (ax/enrich-with-args then input)))))))))

(defn- open-text-document! [dispatch! context options]
  (let [{:keys [ex/then app/content]} options]
    (if-not then
      (vscode/window.showInputBox options)
      (-> (vscode/workspace.openTextDocument #js {:content content})
          (.then (fn [document]
                   (when document
                     (dispatch! context (ax/enrich-with-args then document)))))))))

(defn perform-effect! [dispatch! context effect]
  (match effect
    [:vscode/fx.show-information-message & args]
    (apply vscode/window.showInformationMessage args)

    [:vscode/fx.show-error-message & args]
    (apply vscode/window.showErrorMessage args)

    [:vscode/fx.show-input-box options]
    (show-input-box! dispatch! context options)

    [:vscode/fx.window.open-text-document options]
    (open-text-document! dispatch! context options)

    [:vscode/fx.workspace.open-text-document options]
    (let [{:keys [open/uri ex/then]} options]
      (-> (vscode/workspace.openTextDocument uri)
          (.then (fn [document]
                   (when (and document then)
                     (dispatch! context (ax/enrich-with-args then document))))
                 (fn [error]
                   (js/console.error "Error opening or showing document:" uri error)))))

    [:vscode/fx.show-text-document document]
    (vscode/window.showTextDocument document)

    [:vscode/fx.set-context k v]
    (vscode/commands.executeCommand "setContext" (str k) v)

    :else
    (js/console.error "Unknown effect:" (pr-str effect))))

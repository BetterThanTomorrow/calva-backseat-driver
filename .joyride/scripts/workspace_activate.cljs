(ns workspace-activate
  "Joyride runs the first workspace folder's activate script, and has that folder's
   `.joyride/` as its classpath. When another folder in the workspace has a dev
   environment library, `.joyride/src/dev_environment.cljs`, this loads it from
   there and starts the dev environment."
  (:require ["fs" :as fs]
            ["path" :as path]
            ["vscode" :as vscode]
            [joyride.core :as joyride]))

(defn- dev-environment-file []
  (->> vscode/workspace.workspaceFolders
       (map #(path/join (.. % -uri -fsPath) ".joyride" "src" "dev_environment.cljs"))
       (filter fs/existsSync)
       first))

(when (= (joyride/invoked-script) joyride/*file*)
  (when-let [file (dev-environment-file)]
    (.then (joyride/load-file file)
           (fn [_]
             ((resolve 'dev-environment/start!))))))

"ok"

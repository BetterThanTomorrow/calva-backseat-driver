(ns test-project
  "Starts and stops what the Backseat Driver test protocol needs in this window:
   the five REPL sessions, two browser tabs on the shadow-cljs app, and two tui
   processes. Every step looks at what is already running first, so `start!`
   can run again at any time without opening anything twice."
  (:require ["fs" :as fs]
            ["net" :as net]
            ["path" :as path]
            ["vscode" :as vscode]))

(def shadow-sequence
  {:name "Shadow CLJS (browser + tui)"
   :projectRootPath ["."]
   :cljsType "shadow-cljs"
   :projectType "shadow-cljs"
   :replSessionNames {:primary "shadow-clj"
                      :secondary "shadow-cljs"}
   :replSessionFilePatterns {:primary {:always-claim ["deps.edn" "shadow-cljs.edn"]}
                             :secondary {:always-claim ["**/app.cljs" "**/tui.cljs"]}}
   :menuSelections {:cljAliases ["dev"]
                    :cljsLaunchBuilds [":app" ":tui"]
                    :cljsDefaultBuild ":app"}})

(def clojure-sequences
  [{:name "Mini Clojure Project"
    :projectRootPath ["."]
    :cljsType "none"
    :projectType "deps.edn"
    :replSessionNames {:primary "mini-clj"}
    :replSessionFilePatterns {:primary {:always-claim ["src/**/*.clj"]}}
    :menuSelections {:cljAliases ["test"]}}
   {:name "Mini Babashka"
    :projectRootPath ["."]
    :cljsType "none"
    :projectType "babashka"
    :replSessionNames {:primary "bb"}
    :replSessionFilePatterns {:primary {:always-claim ["**/*.bb"]}}}
   {:name "Sub-project Clojure"
    :projectRootPath ["sub-project"]
    :cljsType "none"
    :projectType "deps.edn"
    :replSessionNames {:primary "clj"}
    :replSessionFilePatterns {:primary {:always-claim ["*.clj" "deps.edn"]}}}])

(def app-port 8780)

(def app-url (str "http://localhost:" app-port))

(def app-tab-labels
  "A browser tab on the app has the page title as its label, or the URL when the page did not load."
  #{"Mini CLJS Test" "localhost:8780"})

(def browser-count 2)

(def tui-tasks ["tui 1" "tui 2"])

(def app-script "resources/public/js/main.js")

(def tui-script "out/mini-tui.js")

(def server-token ".shadow-cljs/server.token")

;; Waiting

(defn- sleep+ [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn ^:async wait-for!+
  "Polls `pred+` (a function returning a value or a promise) until it is truthy
   or `timeout-ms` has passed. Resolves to the last value."
  [pred+ timeout-ms]
  (loop [waited 0]
    (let [v (await (pred+))]
      (if (or v (>= waited timeout-ms))
        v
        (do (await (sleep+ 500))
            (recur (+ waited 500)))))))

;; Calva

(defn ^:async calva-repl+ []
  (let [ext (vscode/extensions.getExtension "betterthantomorrow.calva")
        exports (await (.activate ext))]
    (.. exports -v1 -repl)))

(defn ^:async session-keys+ []
  (->> (.listSessions (await (calva-repl+)))
       (map #(.-replSessionKey %))
       set))

(defn ^:async builds+
  "Returns the shadow-cljs builds Calva knows of, by build id, as
   `{\":app\" {:active? true :runtime-count 2}}`."
  []
  (let [sessions (await (.listSessionsAndRuntimes (await (calva-repl+))))]
    (->> (js->clj sessions :keywordize-keys true)
         (mapcat :builds)
         (reduce (fn [acc {:keys [buildId isActive runtimes]}]
                   (assoc acc buildId {:active? isActive
                                       :runtime-count (count runtimes)}))
                 {}))))

(defn ^:async runtime-count+ [build-id]
  (get-in (await (builds+)) [build-id :runtime-count] 0))

(defn ^:async jack-in!+
  "Jacks in with `connect-sequence` unless its primary session is connected, and
   waits for that session. Resolves to `:connected`, `:jacked-in` or `:timed-out`.
   A shadow-cljs secondary session connects only when a runtime of the default
   build does, so `ensure-browsers!+` waits for it."
  [connect-sequence]
  (let [primary (get-in connect-sequence [:replSessionNames :primary])
        connected? #(.then (session-keys+) (fn [ks] (contains? ks primary)))]
    (if (await (connected?))
      :connected
      (do
        ;; Not awaited: the command does not resolve when the sessions are up.
        (vscode/commands.executeCommand "calva.jackIn"
                                        (clj->js {:connectSequence connect-sequence
                                                  :disableAutoSelect true}))
        (if (await (wait-for!+ connected? 180000))
          :jacked-in
          :timed-out)))))

;; shadow-cljs output

(defn- workspace-path [relative]
  (path/join (.. (first vscode/workspace.workspaceFolders) -uri -fsPath) relative))

(defn- modified-ms [relative]
  (let [file (workspace-path relative)]
    (when (fs/existsSync file)
      (.-mtimeMs (fs/statSync file)))))

(defn- port-open?+ [port]
  (js/Promise.
   (fn [resolve]
     (let [socket (net/connect port "127.0.0.1")]
       (.on socket "connect" (fn []
                               (.end socket)
                               (resolve true)))
       (.on socket "error" (fn [_]
                             (resolve false)))))))

(defn ^:async compiled?+
  "True when shadow-cljs serves the app and `output` is newer than the server
   token, which shadow-cljs writes when the server starts. Older output gives a
   runtime that shadow-cljs rejects as stale."
  [output]
  (let [token (modified-ms server-token)
        written (modified-ms output)]
    (and (await (port-open?+ app-port))
         token
         written
         (> written token))))

;; Runtimes

(defn- enough-runtimes?+ [build-id wanted]
  (.then (runtime-count+ build-id) (fn [n] (>= n wanted))))

(defn ^:async ensure-runtimes!+
  "Waits for `ready?+`, then makes `enough?+` true. When `running?`, what is
   already open gets `grace-ms` to connect or reconnect, since Calva lists the
   runtimes only after the cljs session has connected. When runtimes are still
   missing, `replace!+` replaces what is open, since an open tab or process can't
   be matched to its runtime. Resolves to `:connected`, `replaced` or `:timed-out`."
  [{:keys [ready?+ running? grace-ms enough?+ replace!+ replaced]}]
  (cond
    (not (await (wait-for!+ ready?+ 180000))) :timed-out
    (await (wait-for!+ enough?+ (if running? grace-ms 0))) :connected
    :else (do
            (await (replace!+))
            (if (await (wait-for!+ enough?+ 60000))
              replaced
              :timed-out))))

;; Browser tabs

(defn- app-tabs []
  (for [group vscode/window.tabGroups.all
        tab (.-tabs group)
        :when (contains? app-tab-labels (.-label tab))]
    tab))

(defn ^:async open-browser-tab!+ []
  (let [commands (set (await (vscode/commands.getCommands true)))]
    (if (contains? commands "workbench.action.browser.open")
      (await (vscode/commands.executeCommand "workbench.action.browser.open" app-url))
      (await (vscode/commands.executeCommand "cursor.browserView.newTab" app-url)))))

(defn ^:async replace-browser-tabs!+ []
  (await (vscode/window.tabGroups.close (clj->js (app-tabs))))
  (loop [n browser-count]
    (when (pos? n)
      (await (open-browser-tab!+))
      (recur (dec n)))))

(defn ensure-browsers!+
  "Makes the app build have `browser-count` runtimes, in tabs opened once this
   shadow-cljs server has compiled the app."
  [grace-ms]
  (ensure-runtimes!+ {:ready?+ #(compiled?+ app-script)
                      :running? (boolean (seq (app-tabs)))
                      :grace-ms grace-ms
                      :enough?+ #(enough-runtimes?+ ":app" browser-count)
                      :replace!+ replace-browser-tabs!+
                      :replaced :opened}))

;; Tui processes

(defn- running-tui-executions []
  (filter #(contains? (set tui-tasks) (some-> % .-task .-name))
          vscode/tasks.taskExecutions))

(defn ^:async stop-tuis!+ []
  (doseq [execution (running-tui-executions)]
    (.terminate execution))
  (await (wait-for!+ #(empty? (running-tui-executions)) 10000)))

(defn ^:async start-tuis!+ []
  (let [tasks (await (vscode/tasks.fetchTasks))]
    (loop [[label & more] tui-tasks]
      (when label
        (when-let [task (first (filter #(= label (.-name %)) tasks))]
          (await (vscode/tasks.executeTask task)))
        (recur more)))))

(defn ^:async restart-tuis!+ []
  (await (stop-tuis!+))
  (await (start-tuis!+)))

(defn ensure-tuis!+
  "Makes the tui build have one runtime per tui task, each task in its own split
   terminal, started once this shadow-cljs server has compiled the tui."
  [grace-ms]
  (ensure-runtimes!+ {:ready?+ #(compiled?+ tui-script)
                      :running? (boolean (seq (running-tui-executions)))
                      :grace-ms grace-ms
                      :enough?+ #(enough-runtimes?+ ":tui" (count tui-tasks))
                      :replace!+ restart-tuis!+
                      :replaced :started}))

;; Start and stop

(defn ^:async start!
  "Jacks in to shadow-cljs, then the Clojure and Babashka REPLs, while the
   browser tabs and the tui processes come up. Resolves to what each step found
   or did.

   When this call jacks in to shadow-cljs, tabs and tuis that are already open,
   as after a window reload, were opened before the server and run stale code.
   They are replaced without waiting for them to reconnect."
  []
  (let [shadow (await (jack-in!+ shadow-sequence))
        grace-ms (if (= :jacked-in shadow) 0 15000)
        clients (js/Promise.all #js [(ensure-browsers!+ grace-ms)
                                     (ensure-tuis!+ grace-ms)])
        clojure (loop [[s & more] clojure-sequences
                       acc {}]
                  (if s
                    (recur more (assoc acc (:name s) (await (jack-in!+ s))))
                    acc))
        [browsers tuis] (await clients)
        result (assoc clojure
                      (:name shadow-sequence) shadow
                      :browsers browsers
                      :tuis tuis)]
    (println "test-project/start!" (pr-str result))
    result))

(defn ^:async stop!
  "Stops the tui tasks, closes the app's browser tabs, and stops every jack-in."
  []
  (await (stop-tuis!+))
  (await (vscode/window.tabGroups.close (clj->js (app-tabs))))
  (await (vscode/commands.executeCommand "calva.jackOut"))
  ;; The command resolves before the last jack-in has stopped.
  (let [stopped? (await (wait-for!+ #(.then (session-keys+) empty?) 30000))
        result (if stopped? :stopped :timed-out)]
    (println "test-project/stop!" result)
    result))

(comment
  (start!)
  (stop!)
  (builds+)
  (session-keys+)
  :rcf)

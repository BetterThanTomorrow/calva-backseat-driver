(ns calva-backseat-driver.tools.confirmation
  "The confirmation a person is shown before a tool runs: its title and message, from
   the tool's arguments.")

(defn evaluate-code
  [{:keys [code replSessionKey who targetRuntimeId]
    ns :namespace}]
  {:title (cond-> (str "Evaluate as **" (or who "unknown") "** in the **" replSessionKey "** REPL")
            targetRuntimeId
            (str " (runtime " targetRuntimeId ")"))
   :message (str "Evaluate?\n```clojure\n(in-ns " ns ")\n\n" code "\n```")})

(defn list-sessions [_arguments]
  {:title "List REPL Sessions"
   :message "List available REPL sessions"})

(defn symbol-info [{:keys [clojureSymbol]}]
  {:title "Get Symbol Info"
   :message (str "Get info for Clojure symbol: **" clojureSymbol "**")})

(defn clojuredocs [{:keys [clojureSymbol]}]
  {:title "Get ClojureDocs Info"
   :message (str "Look up docs for Clojure symbol: **" clojureSymbol "**")})

(defn output-log [{:keys [query]}]
  {:title "Query REPL Output Log"
   :message (str "Query REPL output log: " query)})

(defn balance-brackets [{:keys [text]}]
  {:title "Infer brackets"
   :message (str "Infer from indents for: " text)})

(defn load-clojure-file [{:keys [filePath replSessionKey who]}]
  {:title (str "Load file as **" (or who "unknown") "**")
   :message (str "Load file: " filePath
                 (when replSessionKey
                   (str " in **" replSessionKey "** REPL")))})

(defn- plural [n word]
  (str n " " word (when (not= 1 n) "s")))

(defn edit-files [{:keys [edits]}]
  {:title "Edit Clojure Files"
   :message (str (plural (count edits) "edit")
                 " across "
                 (plural (count (distinct (map :filePath edits))) "file"))})

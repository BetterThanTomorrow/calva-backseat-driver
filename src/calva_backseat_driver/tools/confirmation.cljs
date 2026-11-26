(ns calva-backseat-driver.tools.confirmation
  "What a person is shown before a tool runs: the invocation message, and the title and
   message of the confirmation, from the tool's arguments.")

(defn- evaluate-code
  [{:keys [code replSessionKey who targetRuntimeId]
    ns :namespace}]
  {:title (cond-> (str "Evaluate as **" (or who "unknown") "** in the **" replSessionKey "** REPL")
            targetRuntimeId
            (str " (runtime " targetRuntimeId ")"))
   :message (str "Evaluate?\n```clojure\n(in-ns " ns ")\n\n" code "\n```")})

(defn- list-sessions [_arguments]
  {:title "List REPL Sessions"
   :message "List available REPL sessions"})

(defn- symbol-info [{:keys [clojureSymbol]}]
  {:title "Get Symbol Info"
   :message (str "Get info for Clojure symbol: **" clojureSymbol "**")})

(defn- clojuredocs [{:keys [clojureSymbol]}]
  {:title "Get ClojureDocs Info"
   :message (str "Look up docs for Clojure symbol: **" clojureSymbol "**")})

(defn- output-log [{:keys [query]}]
  {:title "Query REPL Output Log"
   :message (str "Query REPL output log: " query)})

(defn- balance-brackets [{:keys [text]}]
  {:title "Infer brackets"
   :message (str "Infer from indents for: " text)})

(defn- load-clojure-file [{:keys [filePath replSessionKey who]}]
  {:title (str "Load file as **" (or who "unknown") "**")
   :message (str "Load file: " filePath
                 (when replSessionKey
                   (str " in **" replSessionKey "** REPL")))})

(defn- plural [n word]
  (str n " " word (when (not= 1 n) "s")))

(defn- edit-files [{:keys [edits]}]
  {:title "Edit Clojure Files"
   :message (str (plural (count edits) "edit")
                 " across "
                 (plural (count (distinct (map :filePath edits))) "file"))})

(def confirmations
  "For each tool: its invocation message, and the function from its arguments to the
   confirmation's `:title` and `:message`."
  {"clojure_evaluate_code"
   {:tool/invocation-message "Evaluating code"
    :tool/confirmation #'evaluate-code}

   "clojure_list_sessions"
   {:tool/invocation-message "Listing REPL sessions"
    :tool/confirmation #'list-sessions}

   "clojure_symbol_info"
   {:tool/invocation-message "Getting symbol info"
    :tool/confirmation #'symbol-info}

   "clojuredocs_info"
   {:tool/invocation-message "Looking up ClojureDocs"
    :tool/confirmation #'clojuredocs}

   "clojure_repl_output_log"
   {:tool/invocation-message "Querying REPL output log"
    :tool/confirmation #'output-log}

   "clojure_balance_brackets"
   {:tool/invocation-message "Inferred brackets"
    :tool/confirmation #'balance-brackets}

   "clojure_load_file"
   {:tool/invocation-message "Loading file"
    :tool/confirmation #'load-clojure-file}

   "clojure_edit_files"
   {:tool/invocation-message "Editing Clojure files"
    :tool/confirmation #'edit-files}})

(defn prepared-invocation
  "Returns what VS Code shows before `tool` runs with `arguments`: the invocation message
   and the confirmation's title and message."
  [tool arguments]
  (let [{:tool/keys [invocation-message confirmation]} (get confirmations tool)]
    {:invocationMessage invocation-message
     :confirmationMessages (confirmation arguments)}))

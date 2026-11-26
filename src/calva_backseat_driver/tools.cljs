(ns calva-backseat-driver.tools
  "The tool table, the one function each tool runs through for both the Language Model
   API and MCP, and the Language Model API's entry point."
  (:require
   ["vscode" :as vscode]
   [calva-backseat-driver.bracket-balance :as balance]
   [calva-backseat-driver.integrations.calva.output :as output]
   [calva-backseat-driver.integrations.calva.session-runtimes :as session-runtimes]
   [calva-backseat-driver.tools.confirmation :as confirmation]
   [calva-backseat-driver.tools.content :as content]
   [calva-backseat-driver.tools.evaluation :as evaluation]))

;; The tools. Each takes `perform-effect!` and a tool call, `{:call/arguments … :call/state …}`,
;; and returns a promise of `{:result/data …}`, with `:result/error? true` when the tool
;; failed and `:result/log` with the log lines it writes. A tool touches Calva and VS Code
;; through `perform-effect!`, which takes an effect vector and returns a promise of what
;; the effect gave.

(defn- result-of+
  "Returns a promise of the result map for the data that `data+` resolves to."
  [data+]
  (.then (js/Promise.resolve data+)
         (fn [data]
           {:result/data data})))

(defn sessions-result
  "Returns the result of the session listing for Calva's `sessions`, with every runtime
   of each build when the call's arguments ask for it."
  [{:keys [includeAllRuntimes]} sessions]
  {:result/data {:sessions (mapv #(session-runtimes/project-session % (true? includeAllRuntimes))
                                 sessions)}})

(defn- list-sessions+
  "Lists Calva's REPL sessions."
  [perform-effect! {:call/keys [arguments]}]
  (.then (perform-effect! [:calva/fx.list-sessions-and-runtimes])
         (fn [sessions]
           (sessions-result arguments sessions))))

(defn- symbol-info+
  "Looks the call's symbol up through Calva."
  [perform-effect! {:call/keys [arguments]}]
  (let [{:keys [clojureSymbol replSessionKey]
         ns :namespace} arguments]
    (result-of+ (perform-effect! [:calva/fx.symbol-info clojureSymbol replSessionKey ns]))))

(defn- clojuredocs+
  "Looks the call's symbol up on ClojureDocs through Calva."
  [perform-effect! {:call/keys [arguments]}]
  (result-of+ (perform-effect! [:calva/fx.clojuredocs (:clojureSymbol arguments)])))

(defn- output-log+
  "Runs the call's Datalog query on the output log."
  [_perform-effect! {:call/keys [arguments state]}]
  (result-of+ (output/query (:calva/output-log state) (:query arguments) (:inputs arguments))))

(defn- balance-brackets+
  "Balances the brackets of the call's text from its indentation."
  [_perform-effect! {:call/keys [arguments]}]
  (result-of+ (balance/infer-parens-response (:text arguments))))

(defn load-file-input-error
  "Returns the error for a load file call that can't run, or nil."
  [{:keys [replSessionKey who]}]
  (if (evaluation/blank-argument? replSessionKey)
    "The `replSessionKey` parameter is required. Use `clojure_list_sessions` to discover available sessions."
    (evaluation/who-error who)))

(defn load-file-result
  "Returns the result of a file load for what Calva gave: the load's result, or the
   error it failed with. A load that fails is a failed tool call."
  [{:load/keys [result error] :as load}]
  (cond
    error
    {:result/data {:error error}
     :result/error? true
     :result/log [[:debug "[Server] Load file failed:" error]]}

    ;; A load that failed with an error that has no message
    (contains? load :load/error)
    {:result/data {:error nil}}

    :else
    {:result/data {:result (or result "nil")}}))

(defn- load-clojure-file+
  "Loads the call's file through Calva."
  [perform-effect! {:call/keys [arguments]}]
  (let [{:keys [filePath replSessionKey who]} arguments]
    (if-let [input-error (load-file-input-error arguments)]
      (result-of+ {:error input-error})
      (.then (perform-effect! [:calva/fx.load-file filePath replSessionKey who])
             load-file-result))))

(defn- edit-files+
  "Applies the call's edits to the files they name."
  [perform-effect! {:call/keys [arguments state]}]
  (result-of+ (perform-effect! [:calva/fx.edit-files
                                (:edits arguments)
                                {:editor/search-padding (:vscode/config.editor.fuzzyLineTargetingPadding state)
                                 :editor/context-padding (:vscode/config.editor.lineContextResponsePadding state)}])))

;; The tool table

(def tools
  "One row per tool: the var that runs it, what it reads from the app state, the log
   line a call writes (a keyword in it stands for that argument of the call), the
   default for its `maxImages` argument when images in its result become image parts,
   and what a person is shown before it runs."
  {"clojure_evaluate_code"
   {:tool/function #'evaluation/evaluate-code+
    :tool/reads [:vscode/config.evaluation.maxLength :vscode/config.evaluation.maxDepth]
    :tool/log-line ["[Server] Evaluating code:" :code]
    :tool/default-max-images 10
    :tool/invocation-message "Evaluating code"
    :tool/confirmation #'confirmation/evaluate-code}

   "clojure_list_sessions"
   {:tool/function #'list-sessions+
    :tool/log-line ["[Server] Listing REPL sessions"]
    :tool/invocation-message "Listing REPL sessions"
    :tool/confirmation #'confirmation/list-sessions}

   "clojure_symbol_info"
   {:tool/function #'symbol-info+
    :tool/log-line ["[Server] Getting symbol info for:" :clojureSymbol]
    :tool/invocation-message "Getting symbol info"
    :tool/confirmation #'confirmation/symbol-info}

   "clojuredocs_info"
   {:tool/function #'clojuredocs+
    :tool/log-line ["[Server] Getting clojuredocs for:" :clojureSymbol]
    :tool/invocation-message "Looking up ClojureDocs"
    :tool/confirmation #'confirmation/clojuredocs}

   "clojure_repl_output_log"
   {:tool/function #'output-log+
    :tool/reads [:calva/output-log]
    :tool/log-line ["[Server] Querying output log with:" :query]
    :tool/default-max-images 0
    :tool/invocation-message "Querying REPL output log"
    :tool/confirmation #'confirmation/output-log}

   "clojure_balance_brackets"
   {:tool/function #'balance-brackets+
    :tool/log-line ["[Server] Infering brackets for:" :text]
    :tool/invocation-message "Inferred brackets"
    :tool/confirmation #'confirmation/balance-brackets}

   "clojure_load_file"
   {:tool/function #'load-clojure-file+
    :tool/log-line ["[Server] Loading file:" :filePath]
    :tool/invocation-message "Loading file"
    :tool/confirmation #'confirmation/load-clojure-file}

   "clojure_edit_files"
   {:tool/function #'edit-files+
    :tool/reads [:vscode/config.editor.fuzzyLineTargetingPadding
                 :vscode/config.editor.lineContextResponsePadding]
    :tool/invocation-message "Editing Clojure files"
    :tool/confirmation #'confirmation/edit-files}})

(defn log-line
  "Returns the messages of the log line that a call of `tool` with `arguments` writes,
   or nil when the tool writes none."
  [tool arguments]
  (when-let [line (get-in tools [tool :tool/log-line])]
    (mapv (fn [part]
            (if (keyword? part)
              (get arguments part)
              part))
          line)))

(defn prepared-invocation
  "Returns what VS Code shows before `tool` runs with `arguments`: the invocation message
   and the confirmation's title and message."
  [tool arguments]
  (let [{:tool/keys [invocation-message confirmation]} (get tools tool)]
    {:invocationMessage invocation-message
     :confirmationMessages (confirmation arguments)}))

(defn- content-parts
  [{:tool/keys [default-max-images]} {:keys [maxImages]} data]
  (if default-max-images
    (content/parts data (if (some? maxImages) maxImages default-max-images))
    (content/parts data)))

(defn call-tool+
  "Runs the tool of `tool-call`, `{:call/tool … :call/arguments … :call/state …}`, with
   `perform-effect!` for what it does outside. Returns a promise of `{:result/content
   parts}`, with `:result/error? true` when the tool failed and `:result/log` when it
   has log lines to write. The promise rejects when the tool throws."
  [perform-effect! {:call/keys [tool arguments] :as tool-call}]
  (let [{:tool/keys [function] :as row} (get tools tool)]
    (-> (js/Promise.resolve nil)
        (.then (fn [_]
                 (function perform-effect! tool-call)))
        (.then (fn [{:result/keys [data error? log]}]
                 (cond-> {:result/content (content-parts row arguments data)}
                   error? (assoc :result/error? true)
                   log (assoc :result/log log)))))))

;; The Language Model API's entry point

(defn- language-model-part
  "Returns the Language Model API's part for a content part. A text part without text,
   from a tool that answered `undefined`, becomes the text `null`."
  [{:keys [type text mimeType data]}]
  (if (= "image" type)
    (vscode/LanguageModelDataPart.image (js/Uint8Array. (js/Buffer.from data "base64")) mimeType)
    (vscode/LanguageModelTextPart. (or text "null"))))

(defn- rejection-message
  "Returns the message a Language Model API call rejects with for a tool call's
   `result`: the call was refused, or the tool threw. Returns nil for other results."
  [{:result/keys [refused thrown]}]
  (if refused
    "Unknown tool"
    thrown))

(defn- language-model-result
  "Returns the Language Model API's result for a tool call's `result`. Throws when
   the result has a rejection message."
  [{:result/keys [content] :as result}]
  (if-let [message (rejection-message result)]
    (throw (js/Error. message))
    (vscode/LanguageModelToolResult. (into-array (map language-model-part content)))))

(defn- arguments-of [^js options]
  (js->clj (.-input options) :keywordize-keys true))

(defn- language-model-tool
  "Returns the object VS Code calls for `tool`."
  [dispatch! tool]
  #js {:prepareInvocation (fn [^js options _token]
                            (clj->js (prepared-invocation tool (arguments-of options))))
       :invoke (fn [^js options _token]
                 (.then (dispatch! [[:tool/ax.call {:call/tool tool
                                                    :call/arguments (arguments-of options)}]])
                        language-model-result))})

(defn register-language-model-tools
  "Registers every tool in the tool table with the Language Model API. Returns the disposables."
  [dispatch!]
  (mapv (fn [tool]
          (vscode/lm.registerTool tool (language-model-tool dispatch! tool)))
        (keys tools)))

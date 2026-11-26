(ns calva-backseat-driver.tools
  "The tool table, the one function each tool runs through for both the Language Model
   API and MCP, and the Language Model API's entry point."
  (:require
   ["vscode" :as vscode]
   [calva-backseat-driver.bracket-balance :as balance]
   [calva-backseat-driver.integrations.calva.features :as calva]
   [calva-backseat-driver.integrations.calva.output :as output]
   [calva-backseat-driver.tools.confirmation :as confirmation]
   [calva-backseat-driver.tools.content :as content]
   [clojure.string :as string]))

;; The tools. Each takes `dispatch!` and a tool call, `{:call/arguments … :call/state …}`,
;; and returns a promise of `{:result/data …}`, with `:result/error? true` when the tool failed.

(defn- result-of+
  "Returns a promise of the result map for the data that `data+` resolves to."
  [data+]
  (.then (js/Promise.resolve data+)
         (fn [data]
           {:result/data data})))

(defn- evaluate-code+
  "Evaluates the call's code through Calva."
  [dispatch! {:call/keys [arguments state]}]
  (let [{:keys [code replSessionKey who description targetRuntimeId]
         ns :namespace} arguments]
    (if-let [who-error (calva/validate-who who)]
      (result-of+ {:error who-error})
      (result-of+ (calva/evaluate-code+ (cond-> {:uf/dispatch! dispatch!
                                                 :calva/code code
                                                 :calva/repl-session-key replSessionKey
                                                 :calva/who who
                                                 :calva/description description
                                                 :calva/max-length (:vscode/config.evaluation.maxLength state)
                                                 :calva/max-depth (:vscode/config.evaluation.maxDepth state)}
                                          ns (assoc :calva/ns ns)
                                          targetRuntimeId (assoc :calva/target-runtime-id targetRuntimeId)))))))

(defn- list-sessions+
  "Lists Calva's REPL sessions."
  [dispatch! {:call/keys [arguments]}]
  (result-of+ (calva/list-sessions+ {:uf/dispatch! dispatch!
                                     :calva/include-all-runtimes? (true? (:includeAllRuntimes arguments))})))

(defn- symbol-info+
  "Looks the call's symbol up through Calva."
  [dispatch! {:call/keys [arguments]}]
  (let [{:keys [clojureSymbol replSessionKey]
         ns :namespace} arguments]
    (result-of+ (calva/get-symbol-info+ {:uf/dispatch! dispatch!
                                         :calva/clojure-symbol clojureSymbol
                                         :calva/repl-session-key replSessionKey
                                         :calva/ns ns}))))

(defn- clojuredocs+
  "Looks the call's symbol up on ClojureDocs through Calva."
  [dispatch! {:call/keys [arguments]}]
  (result-of+ (calva/get-clojuredocs+ {:uf/dispatch! dispatch!
                                       :calva/clojure-symbol (:clojureSymbol arguments)})))

(defn- output-log+
  "Runs the call's Datalog query on the output log."
  [dispatch! {:call/keys [arguments state]}]
  (let [{:keys [query inputs]} arguments]
    (dispatch! [[:app/ax.log :debug "[Server] Querying output log with:" query]])
    (result-of+ (output/query (:calva/output-log state) query inputs))))

(defn- balance-brackets+
  "Balances the brackets of the call's text from its indentation."
  [dispatch! {:call/keys [arguments]}]
  (result-of+ (balance/infer-parens-response {:uf/dispatch! dispatch!
                                              :calva/text (:text arguments)})))

(defn- blank-session-key? [session-key]
  (or (nil? session-key)
      (and (string? session-key)
           (string/blank? session-key))))

(defn- load-file-input-error
  "Returns the error for a load file call that can't run, or nil."
  [{:keys [replSessionKey who]}]
  (if (blank-session-key? replSessionKey)
    "The `replSessionKey` parameter is required. Use `clojure_list_sessions` to discover available sessions."
    (calva/validate-who who)))

(defn- load-clojure-file+
  "Loads the call's file through Calva. A load that fails is a failed tool call."
  [dispatch! {:call/keys [arguments]}]
  (let [{:keys [filePath replSessionKey who]} arguments]
    (if-let [input-error (load-file-input-error arguments)]
      (result-of+ {:error input-error})
      (.then (js/Promise.resolve (calva/load-file+ {:uf/dispatch! dispatch!
                                                    :calva/file-path filePath
                                                    :calva/repl-session-key replSessionKey
                                                    :calva/who who}))
             (fn [{:keys [error] :as data}]
               (if error
                 {:result/data {:error error}
                  :result/error? true}
                 {:result/data data}))))))

(defn- edit-files+
  "Applies the call's edits to the files they name."
  [_dispatch! {:call/keys [arguments state]}]
  (result-of+ (calva/edit-files+ {:calva/edits (:edits arguments)
                                  :calva/search-padding (:vscode/config.editor.fuzzyLineTargetingPadding state)
                                  :calva/context-padding (:vscode/config.editor.lineContextResponsePadding state)})))

;; The tool table

(def tools
  "One row per tool: the var that runs it, what it reads from the app state, the default for its
   `maxImages` argument when images in its result become image parts, and what a person
   is shown before it runs."
  {"clojure_evaluate_code"
   {:tool/function #'evaluate-code+
    :tool/reads [:vscode/config.evaluation.maxLength
                 :vscode/config.evaluation.maxDepth]
    :tool/default-max-images 10
    :tool/invocation-message "Evaluating code"
    :tool/confirmation #'confirmation/evaluate-code}

   "clojure_list_sessions"
   {:tool/function #'list-sessions+
    :tool/invocation-message "Listing REPL sessions"
    :tool/confirmation #'confirmation/list-sessions}

   "clojure_symbol_info"
   {:tool/function #'symbol-info+
    :tool/invocation-message "Getting symbol info"
    :tool/confirmation #'confirmation/symbol-info}

   "clojuredocs_info"
   {:tool/function #'clojuredocs+
    :tool/invocation-message "Looking up ClojureDocs"
    :tool/confirmation #'confirmation/clojuredocs}

   "clojure_repl_output_log"
   {:tool/function #'output-log+
    :tool/reads [:calva/output-log]
    :tool/default-max-images 0
    :tool/invocation-message "Querying REPL output log"
    :tool/confirmation #'confirmation/output-log}

   "clojure_balance_brackets"
   {:tool/function #'balance-brackets+
    :tool/invocation-message "Inferred brackets"
    :tool/confirmation #'confirmation/balance-brackets}

   "clojure_load_file"
   {:tool/function #'load-clojure-file+
    :tool/invocation-message "Loading file"
    :tool/confirmation #'confirmation/load-clojure-file}

   "clojure_edit_files"
   {:tool/function #'edit-files+
    :tool/reads [:vscode/config.editor.fuzzyLineTargetingPadding
                 :vscode/config.editor.lineContextResponsePadding]
    :tool/invocation-message "Editing Clojure files"
    :tool/confirmation #'confirmation/edit-files}})

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
  "Runs the tool of `tool-call`, `{:call/tool … :call/arguments … :call/state …}`.
   Returns a promise of `{:result/content parts}`, with `:result/error? true` when the
   tool failed. The promise rejects when the tool throws."
  [dispatch! {:call/keys [tool arguments] :as tool-call}]
  (let [{:tool/keys [function] :as row} (get tools tool)]
    (-> (js/Promise.resolve nil)
        (.then (fn [_]
                 (function dispatch! tool-call)))
        (.then (fn [{:result/keys [data error?]}]
                 (cond-> {:result/content (content-parts row arguments data)}
                   error? (assoc :result/error? true)))))))

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

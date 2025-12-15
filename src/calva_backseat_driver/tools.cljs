(ns calva-backseat-driver.tools
  (:require
   ["vscode" :as vscode]
   [calva-backseat-driver.bracket-balance :as balance]
   [calva-backseat-driver.integrations.calva.features :as calva]
   [promesa.core :as p]))

(defn EvaluateClojureCodeTool [dispatch!]
  #js {:prepareInvocation (fn prepareInvocation [^js options _token]
                            (let [code (-> options .-input .-code)
                                  ns (-> options .-input .-namespace)
                                  session-key (-> options .-input .-replSessionKey)
                                  message (str "Evaluate?\n```clojure\n(in-ns " ns ")\n\n" code "\n```")]
                              #js {:invocationMessage "Evaluating code"
                                   :confirmationMessages #js {:title (str "Evaluate code in the **" session-key "** REPL")
                                                              :message message}}))

       :invoke (fn invoke [^js options _token]
                 (p/let [code (.-code (.-input options))
                         ns (.-namespace (.-input options))
                         session-key (.-replSessionKey (.-input options))
                         result (calva/evaluate-code+ {:ex/dispatch! dispatch!
                                                       :calva/code code
                                                       :calva/ns ns
                                                       :calva/repl-session-key session-key})]
                   (vscode/LanguageModelToolResult.
                    #js [(vscode/LanguageModelTextPart.
                          (js/JSON.stringify (clj->js result)))])))})

(defn GetSymbolInfoTool [dispatch!]
  #js {:prepareInvocation (fn prepareInvocation [^js options _token]
                            (let [symbol (-> options .-input .-clojureSymbol)
                                  message (str "Get info for Clojure symbol: **" symbol "**")]
                              #js {:invocationMessage "Getting symbol info"
                                   :confirmationMessages #js {:title "Get Symbol Info"
                                                              :message message}}))

       :invoke (fn invoke [^js options _token]
                 (p/let [symbol (-> options .-input .-clojureSymbol)
                         ns (-> options .-input .-namespace)
                         session-key (-> options .-input .-replSessionKey)
                         result (calva/get-symbol-info+ {:ex/dispatch! dispatch!
                                                         :calva/clojure-symbol symbol
                                                         :calva/ns ns
                                                         :calva/repl-session-key session-key})]
                   (vscode/LanguageModelToolResult.
                    #js [(vscode/LanguageModelTextPart.
                          (js/JSON.stringify (clj->js result)))])))})

(defn GetClojureDocsTool [dispatch!]
  #js {:prepareInvocation (fn prepareInvocation [^js options _token]
                            (let [symbol (-> options .-input .-clojureSymbol)
                                  message (str "Look up docs for Clojure symbol: **" symbol "**")]
                              #js {:invocationMessage "Looking up ClojureDocs"
                                   :confirmationMessages #js {:title "Get ClojureDocs Info"
                                                              :message message}}))

       :invoke (fn invoke [^js options _token]
                 (p/let [symbol (-> options .-input .-clojureSymbol)
                         result (calva/get-clojuredocs+ {:ex/dispatch! dispatch!
                                                         :calva/clojure-symbol symbol})]
                   (vscode/LanguageModelToolResult.
                    #js [(vscode/LanguageModelTextPart.
                          (js/JSON.stringify (clj->js result)))])))})

(defn GetOutputLogTool [dispatch!]
  #js {:prepareInvocation (fn prepareInvocation [^js options _token]
                            (let [since-line (-> options .-input .-sinceLine)
                                  message (str "Fetch REPL output from line " since-line)]
                              #js {:invocationMessage "Fetching REPL output"
                                   :confirmationMessages #js {:title "Get REPL Output Log"
                                                              :message message}}))

       :invoke (fn invoke [^js options _token]
                 (let [since-line (-> options .-input .-sinceLine)
                       result (calva/get-output {:ex/dispatch! dispatch!
                                                 :calva/since-line since-line})]
                   (vscode/LanguageModelToolResult.
                    #js [(vscode/LanguageModelTextPart.
                          (js/JSON.stringify (clj->js result)))])))})

(defn InferBracketsTool [dispatch!]
  #js {:prepareInvocation (fn prepareInvocation [^js options _token]
                            (let [text (-> options .-input .-text)
                                  message (str "Infer from indents for: " text)]
                              #js {:invocationMessage "Inferred brackets"
                                   :confirmationMessages #js {:title "Infer brackets"
                                                              :message message}}))

       :invoke (fn invoke [^js options _token]
                 (let [text (-> options .-input .-text)
                       result (balance/infer-parens-response {:ex/dispatch! dispatch!
                                                              :calva/text text})]
                   (vscode/LanguageModelToolResult.
                    #js [(vscode/LanguageModelTextPart.
                          (js/JSON.stringify result))])))})

(defn- ReplaceOrInsertTopLevelFormTool [dispatch! ranges-fn-key confirm-prefix invoked-prefix]
  #js {:prepareInvocation (fn prepareInvocation [^js options _token]
                            (let [file-path (-> options .-input .-filePath)
                                  line (-> options .-input .-line)
                                  target-line (-> options .-input .-targetLineText)
                                  new-form (-> options .-input .-newForm)
                                  message (str confirm-prefix " form at line " line
                                               (when target-line (str " (targeting: '" target-line "')"))
                                               " in " file-path
                                               " width:\n" new-form)]
                              #js {:invocationMessage (str invoked-prefix " top-level form")
                                   :confirmationMessages #js {:title (str confirm-prefix " Top-Level Form")
                                                              :message message}}))

       :invoke (fn invoke [^js options _token]
                 (p/let [file-path (-> options .-input .-filePath)
                         line (some-> options .-input .-line)
                         target-line (-> options .-input .-targetLineText)
                         new-form (-> options .-input .-newForm)
                         result (if (= ranges-fn-key :currentTopLevelForm)
                                  (calva/replace-top-level-form+ {:ex/dispatch! dispatch!
                                                                  :calva/file-path file-path
                                                                  :calva/line line
                                                                  :calva/target-line-text target-line
                                                                  :calva/new-form new-form})
                                  (calva/insert-top-level-form+ {:ex/dispatch! dispatch!
                                                                 :calva/file-path file-path
                                                                 :calva/line line
                                                                 :calva/target-line-text target-line
                                                                 :calva/new-form new-form}))]
                   (vscode/LanguageModelToolResult.
                    #js [(vscode/LanguageModelTextPart.
                          (js/JSON.stringify (clj->js result)))])))})

(defn ReplaceTopLevelFormTool [dispatch!]
  (ReplaceOrInsertTopLevelFormTool dispatch! :currentTopLevelForm "Replace" "Replaced"))

(defn InsertTopLevelFormTool [dispatch!]
  (ReplaceOrInsertTopLevelFormTool dispatch! :insertionPoint "Insert" "Inserted"))

(defn StructuralCreateFileTool [dispatch!]
  #js {:prepareInvocation (fn prepareInvocation [^js options _token]
                            (let [file-path (-> options .-input .-filePath)
                                  message (str "Create file: " file-path)]
                              #js {:invocationMessage "Creating Clojure file"
                                   :confirmationMessages #js {:title "Create Clojure File"
                                                              :message message}}))

       :invoke (fn invoke [^js options _token]
                 (p/let [file-path (-> options .-input .-filePath)
                         content (-> options .-input .-content)
                         result (calva/structural-create-file+ {:ex/dispatch! dispatch!
                                                                :calva/file-path file-path
                                                                :calva/content content})]
                   (vscode/LanguageModelToolResult.
                    #js [(vscode/LanguageModelTextPart.
                          (js/JSON.stringify (clj->js result)))])))})

(defn AppendCodeTool [dispatch!]
  #js {:prepareInvocation (fn prepareInvocation [^js options _token]
                            (let [file-path (-> options .-input .-filePath)
                                  message (str "Append form to: " file-path)]
                              #js {:invocationMessage "Appending code"
                                   :confirmationMessages #js {:title "Append code"
                                                              :message message}}))

       :invoke (fn invoke [^js options _token]
                 (p/let [file-path (-> options .-input .-filePath)
                         code (-> options .-input .-code)
                         result (calva/append-code+ {:ex/dispatch! dispatch!
                                                     :calva/file-path file-path
                                                     :calva/code code})]
                   (vscode/LanguageModelToolResult.
                    #js [(vscode/LanguageModelTextPart.
                          (js/JSON.stringify (clj->js result)))])))})

(defn ListSessionsTool [dispatch!]
  #js {:prepareInvocation (fn prepareInvocation [^js _options _token]
                            #js {:invocationMessage "Listing REPL sessions"
                                 :confirmationMessages #js {:title "List REPL Sessions"
                                                            :message "List available REPL sessions"}})

       :invoke (fn invoke [^js _options _token]
                 (p/let [result (calva/list-sessions+ {:ex/dispatch! dispatch!})]
                   (vscode/LanguageModelToolResult.
                    #js [(vscode/LanguageModelTextPart.
                          (js/JSON.stringify result))])))})

(defn register-language-model-tools [dispatch!]
  ;; Set context for conditional tool visibility in UI
  (dispatch! [[:app/ax.set-when-context :calva-backseat-driver/listSessionsAvailable
               (calva/exists-list-sessions?)]])
  (cond-> []
    :always
    (conj (vscode/lm.registerTool
           "clojure_evaluate_code"
           (#'EvaluateClojureCodeTool dispatch!)))

    (calva/exists-list-sessions?)
    (conj (vscode/lm.registerTool
           "clojure_list_sessions"
           (#'ListSessionsTool dispatch!)))

    (calva/exists-get-symbol-info?)
    (conj (vscode/lm.registerTool
           "clojure_symbol_info"
           (#'GetSymbolInfoTool dispatch!)))

    (calva/exists-get-clojuredocs?)
    (conj (vscode/lm.registerTool
           "clojuredocs_info"
           (#'GetClojureDocsTool dispatch!)))

    (calva/exists-on-output?)
    (conj (vscode/lm.registerTool
           "clojure_repl_output_log"
           (#'GetOutputLogTool dispatch!)))

    :always
    (conj (vscode/lm.registerTool
           "clojure_balance_brackets"
           (#'InferBracketsTool dispatch!)))

    :always
    (conj (vscode/lm.registerTool
           "replace_top_level_form"
           (#'ReplaceTopLevelFormTool dispatch!)))

    :always
    (conj (vscode/lm.registerTool
           "insert_top_level_form"
           (#'InsertTopLevelFormTool dispatch!)))

    :always
    (conj (vscode/lm.registerTool
           "clojure_create_file"
           (#'StructuralCreateFileTool dispatch!)))

    :always
    (conj (vscode/lm.registerTool
           "clojure_append_code"
           (#'AppendCodeTool dispatch!)))))

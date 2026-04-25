(ns calva-backseat-driver.tools
  (:require
   ["vscode" :as vscode]
   [calva-backseat-driver.bracket-balance :as balance]
   [calva-backseat-driver.integrations.calva.features :as calva]
   [promesa.core :as p]
   [clojure.string :as string]
   [clojure.walk :as walk]))

(defn reduce-images
  "Walks a Clojure data structure, replacing data:image/ URLs with <<image-N>>
   references. Returns {:data <cleaned-data> :images [{:mime :data :base64} ...]}
   or nil if no images found. Images beyond max-images (default 20) are replaced
   with <<image-N-capped>> markers but not included in the returned images."
  [data & {:keys [max-images]}]
  (let [max-images (or max-images 20)
        !images (atom [])
        !idx (atom 0)
        pattern #"data:(image/[^;]+);base64,([A-Za-z0-9+/=\s]+)"
        reduced (walk/postwalk
                 (fn [x]
                   (if (and (string? x) (string/includes? x "data:image/"))
                     (let [matches (re-seq pattern x)]
                       (if (seq matches)
                         (reduce (fn [s [full-match mime base64]]
                                   (let [idx (swap! !idx inc)]
                                     (if (<= idx max-images)
                                       (let [clean (string/replace base64 #"\s" "")]
                                         (swap! !images conj {:mime mime
                                                              :data (js/Uint8Array. (js/Buffer.from clean "base64"))
                                                              :base64 clean})
                                         (string/replace-first s full-match (str "<<image-" idx ">>")))
                                       (string/replace-first s full-match (str "<<image-" idx "-capped>>")))))
                                 x matches)
                         x))
                     x))
                 data)]
    (when (seq @!images)
      {:data reduced
       :images @!images})))

(defn- tool-result-with-images
  "Builds a LanguageModelToolResult, extracting embedded images as separate parts.
   Works on Clojure data (before clj->js)."
  [data & {:keys [max-images]}]
  (if-let [{:keys [data images]} (reduce-images data :max-images max-images)]
    (let [text-part (vscode/LanguageModelTextPart. (js/JSON.stringify (clj->js data)))
          image-parts (mapv (fn [{:keys [mime data]}]
                              (vscode/LanguageModelDataPart.image data mime))
                            images)]
      (vscode/LanguageModelToolResult.
       (into-array (cons text-part image-parts))))
    (vscode/LanguageModelToolResult.
     #js [(vscode/LanguageModelTextPart.
           (js/JSON.stringify (clj->js data)))])))

(defn mcp-content-with-images
  "Builds MCP content array, extracting embedded images as separate items.
   Works on Clojure data (before clj->js)."
  [data & {:keys [max-images]}]
  (if-let [{:keys [data images]} (reduce-images data :max-images max-images)]
    (into [{:type "text" :text (js/JSON.stringify (clj->js data))}]
          (map (fn [{:keys [mime base64]}]
                 {:type "image" :mimeType mime :data base64}))
          images)
    [{:type "text" :text (js/JSON.stringify (clj->js data))}]))

(defn EvaluateClojureCodeTool [dispatch!]
  #js {:prepareInvocation (fn prepareInvocation [^js options _token]
                            (let [code (-> options .-input .-code)
                                  ns (-> options .-input .-namespace)
                                  session-key (-> options .-input .-replSessionKey)
                                  who (-> options .-input .-who)
                                  message (str "Evaluate?\n```clojure\n(in-ns " ns ")\n\n" code "\n```")]
                              #js {:invocationMessage "Evaluating code"
                                   :confirmationMessages #js {:title (str "Evaluate as **" (or who "unknown") "** in the **" session-key "** REPL")
                                                              :message message}}))

       :invoke (fn invoke [^js options _token]
                 (let [who (-> options .-input .-who)]
                   (if-let [who-error (calva/validate-who who)]
                     (p/resolved
                      (vscode/LanguageModelToolResult.
                       #js [(vscode/LanguageModelTextPart.
                             (js/JSON.stringify (clj->js {:error who-error})))]))
                     (p/let [code (-> options .-input .-code)
                             ns (-> options .-input .-namespace)
                             session-key (-> options .-input .-replSessionKey)
                             description (-> options .-input .-description)
                             result (calva/evaluate-code+ {:ex/dispatch! dispatch!
                                                           :calva/code code
                                                           :calva/ns ns
                                                           :calva/repl-session-key session-key
                                                           :calva/who who
                                                           :calva/description description})]
                       (tool-result-with-images result)))))})


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
                            (let [query (-> options .-input .-query)
                                  message (str "Query REPL output log: " query)]
                              #js {:invocationMessage "Querying REPL output log"
                                   :confirmationMessages #js {:title "Query REPL Output Log"
                                                              :message message}}))

       :invoke (fn invoke [^js options _token]
                 (let [query (-> options .-input .-query)
                       inputs (some-> options .-input .-inputs js->clj)
                       result (calva/query-output {:ex/dispatch! dispatch!
                                                   :calva/query-edn-str query
                                                   :calva/inputs inputs})]
                   (tool-result-with-images result)))})


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

(defn LoadFileTool [dispatch!]
  #js {:prepareInvocation (fn prepareInvocation [^js options _token]
                            (let [file-path (-> options .-input .-filePath)
                                  session-key (-> options .-input .-replSessionKey)
                                  who (-> options .-input .-who)
                                  message (str "Load file: " file-path
                                               (when session-key (str " in **" session-key "** REPL")))]
                              #js {:invocationMessage "Loading file"
                                   :confirmationMessages #js {:title (str "Load file as **" (or who "unknown") "**")
                                                              :message message}}))

       :invoke (fn invoke [^js options _token]
                 (let [who (-> options .-input .-who)]
                   (if-let [who-error (calva/validate-who who)]
                     (p/resolved
                      (vscode/LanguageModelToolResult.
                       #js [(vscode/LanguageModelTextPart.
                             (js/JSON.stringify (clj->js {:error who-error})))]))
                     (p/let [file-path (-> options .-input .-filePath)
                             session-key (-> options .-input .-replSessionKey)
                             result (calva/load-file+ {:ex/dispatch! dispatch!
                                                       :calva/file-path file-path
                                                       :calva/repl-session-key session-key
                                                       :calva/who who})]
                       (if (:error result)
                         (vscode/LanguageModelToolResult.
                          #js [(vscode/LanguageModelTextPart.
                                (js/JSON.stringify (clj->js {:error (:error result)})))])
                         (vscode/LanguageModelToolResult.
                          #js [(vscode/LanguageModelTextPart.
                                (js/JSON.stringify (clj->js result)))]))))))})
(defn register-language-model-tools [dispatch!]
  ;; Set context for conditional tool visibility in UI
  (dispatch! [[:app/ax.set-when-context :calva-backseat-driver/listSessionsAvailable
               (calva/exists-list-sessions?)]
              [:app/ax.set-when-context :calva-backseat-driver/loadFileAvailable
               (calva/exists-load-file?)]])
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
           (#'AppendCodeTool dispatch!)))

    (calva/exists-load-file?)
    (conj (vscode/lm.registerTool
           "clojure_load_file"
           (#'LoadFileTool dispatch!)))))

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
   with <<image-N-capped>> markers but not included in the returned images.
   When max-images is 0, all images are capped (stripped from text, none returned)."
  [data & {:keys [max-images]}]
  (let [max-images (if (some? max-images) max-images 20)
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
    (when (pos? @!idx)
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

(defn- text-tool-result [data]
  (vscode/LanguageModelToolResult.
   #js [(vscode/LanguageModelTextPart.
         (js/JSON.stringify (clj->js data)))]))

(defn- text-tool-result-raw [js-data]
  (vscode/LanguageModelToolResult.
   #js [(vscode/LanguageModelTextPart.
         (js/JSON.stringify js-data))]))

(defn- prepare-invocation-messages [invocation-msg confirm-title confirm-msg]
  #js {:invocationMessage invocation-msg
       :confirmationMessages #js {:title confirm-title
                                  :message confirm-msg}})

(defn EvaluateClojureCodeTool [dispatch!]
  #js {:prepareInvocation (fn prepareInvocation [^js options _token]
                            (let [code (-> options .-input .-code)
                                  ns (-> options .-input .-namespace)
                                  session-key (-> options .-input .-replSessionKey)
                                  who (-> options .-input .-who)
                                  target-runtime-id (-> options .-input .-targetRuntimeId)
                                  message (str "Evaluate?\n```clojure\n(in-ns " ns ")\n\n" code "\n```")
                                  title (cond-> (str "Evaluate as **" (or who "unknown") "** in the **" session-key "** REPL")
                                          target-runtime-id
                                          (str " (runtime " target-runtime-id ")"))]
                              #js {:invocationMessage "Evaluating code"
                                   :confirmationMessages #js {:title title
                                                              :message message}}))

       :invoke (fn invoke [^js options _token]
                 (let [who (-> options .-input .-who)
                       max-images (-> options .-input .-maxImages)]
                   (if-let [who-error (calva/validate-who who)]
                     (p/resolved
                      (vscode/LanguageModelToolResult.
                       #js [(vscode/LanguageModelTextPart.
                             (js/JSON.stringify (clj->js {:error who-error})))]))
                     (p/let [code (-> options .-input .-code)
                             ns (-> options .-input .-namespace)
                             session-key (-> options .-input .-replSessionKey)
                             description (-> options .-input .-description)
                             target-runtime-id (-> options .-input .-targetRuntimeId)
                             result (calva/evaluate-code+ {:ex/dispatch! dispatch!
                                                           :calva/code code
                                                           :calva/ns ns
                                                           :calva/repl-session-key session-key
                                                           :calva/who who
                                                           :calva/description description
                                                           :calva/target-runtime-id target-runtime-id})]
                       (tool-result-with-images result :max-images (if (some? max-images) max-images 10))))))})


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
                   (text-tool-result result)))})

(defn- simple-tool
  "Build a VS Code Language Model tool with one input field, simple messages, and a direct invoke function.
   Returns a function that takes dispatch! and produces a JS tool object."
  [{:keys [input-field invocation-msg confirm-title confirm-msg-fn invoke-fn result-fn]
    :or {result-fn text-tool-result}}]
  (fn [dispatch!]
    #js {:prepareInvocation
         (fn prepareInvocation [^js options _token]
           (let [input (aget (.-input options) input-field)]
             (prepare-invocation-messages invocation-msg confirm-title (confirm-msg-fn input))))
         :invoke
         (fn invoke [^js options _token]
           (p/let [input (aget (.-input options) input-field)
                   result (invoke-fn dispatch! input)]
             (result-fn result)))}))

(def GetClojureDocsTool
  (simple-tool
   {:input-field "clojureSymbol"
    :invocation-msg "Looking up ClojureDocs"
    :confirm-title "Get ClojureDocs Info"
    :confirm-msg-fn #(str "Look up docs for Clojure symbol: **" % "**")
    :invoke-fn (fn [dispatch! symbol]
                 (calva/get-clojuredocs+ {:ex/dispatch! dispatch!
                                          :calva/clojure-symbol symbol}))}))

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
                       max-images (-> options .-input .-maxImages)
                       result (calva/query-output {:ex/dispatch! dispatch!
                                                   :calva/query-edn-str query
                                                   :calva/inputs inputs})]
                   (tool-result-with-images result :max-images (if (some? max-images) max-images 0))))})


(def InferBracketsTool
  (simple-tool
   {:input-field "text"
    :invocation-msg "Inferred brackets"
    :confirm-title "Infer brackets"
    :confirm-msg-fn #(str "Infer from indents for: " %)
    :invoke-fn (fn [dispatch! text]
                 (balance/infer-parens-response {:ex/dispatch! dispatch!
                                                 :calva/text text}))
    :result-fn text-tool-result-raw}))

(defn ListSessionsTool [dispatch!]
  #js {:prepareInvocation (fn prepareInvocation [^js _options _token]
                            #js {:invocationMessage "Listing REPL sessions"
                                 :confirmationMessages #js {:title "List REPL Sessions"
                                                            :message "List available REPL sessions"}})

       :invoke (fn invoke [^js options _token]
                 (let [include-all-runtimes? (true? (-> options .-input .-includeAllRuntimes))]
                   (p/let [result (calva/list-sessions+ {:ex/dispatch! dispatch!
                                                         :calva/include-all-runtimes? include-all-runtimes?})]
                     (text-tool-result result))))})

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
                     (p/resolved (text-tool-result {:error who-error}))
                     (p/let [file-path (-> options .-input .-filePath)
                             session-key (-> options .-input .-replSessionKey)
                             result (calva/load-file+ {:ex/dispatch! dispatch!
                                                       :calva/file-path file-path
                                                       :calva/repl-session-key session-key
                                                       :calva/who who})]
                       (if (:error result)
                         (text-tool-result {:error (:error result)})
                         (text-tool-result result))))))})

(defn EditFilesTool [dispatch!]
  #js {:prepareInvocation (fn prepareInvocation [^js options _token]
                            (let [edits (js->clj (-> options .-input .-edits) :keywordize-keys true)
                                  file-count (count (distinct (map :filePath edits)))
                                  edit-count (count edits)]
                              #js {:invocationMessage "Editing Clojure files"
                                   :confirmationMessages #js {:title "Edit Clojure Files"
                                                              :message (str edit-count " edit" (when (not= 1 edit-count) "s")
                                                                            " across " file-count " file" (when (not= 1 file-count) "s"))}}))

       :invoke (fn invoke [^js options _token]
                 (p/let [edits (js->clj (-> options .-input .-edits) :keywordize-keys true)
                         result (calva/edit-files+ {:ex/dispatch! dispatch!
                                                    :calva/edits edits})]
                   (text-tool-result result)))})

(defn register-language-model-tools [dispatch!]
  [(vscode/lm.registerTool
    "clojure_evaluate_code"
    (#'EvaluateClojureCodeTool dispatch!))

   (vscode/lm.registerTool
    "clojure_list_sessions"
    (#'ListSessionsTool dispatch!))

   (vscode/lm.registerTool
    "clojure_symbol_info"
    (#'GetSymbolInfoTool dispatch!))

   (vscode/lm.registerTool
    "clojuredocs_info"
    (#'GetClojureDocsTool dispatch!))

   (vscode/lm.registerTool
    "clojure_repl_output_log"
    (#'GetOutputLogTool dispatch!))

   (vscode/lm.registerTool
    "clojure_balance_brackets"
    (#'InferBracketsTool dispatch!))

   (vscode/lm.registerTool
    "clojure_edit_files"
    (#'EditFilesTool dispatch!))

   (vscode/lm.registerTool
    "clojure_load_file"
    (#'LoadFileTool dispatch!))])

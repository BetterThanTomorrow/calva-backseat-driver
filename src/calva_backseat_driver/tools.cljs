(ns calva-backseat-driver.tools
  "The tool table, the one function each tool runs through for both the Language Model
   API and MCP, and the Language Model API's entry point."
  (:require
   ["vscode" :as vscode]
   [calva-backseat-driver.bracket-balance :as balance]
   [calva-backseat-driver.integrations.calva.features :as calva]
   [calva-backseat-driver.tools.confirmation :as confirmation]
   [calva-backseat-driver.tools.content :as content]
   [clojure.string :as string]
   [vscode-mcp.manifest :as manifest]))

;; The tools. Each takes a call, `{:ex/dispatch! … :call/arguments …}`, and returns a
;; promise of `{:result/data …}`, with `:result/error? true` when the tool failed.

(defn- result-of+
  "Returns a promise of the result map for the data that `data+` resolves to."
  [data+]
  (.then (js/Promise.resolve data+)
         (fn [data]
           {:result/data data})))

(defn- evaluate-code+
  [{:ex/keys [dispatch!]
    :call/keys [arguments]}]
  (let [{:keys [code replSessionKey who description targetRuntimeId]
         ns :namespace} arguments]
    (if-let [who-error (calva/validate-who who)]
      (result-of+ {:error who-error})
      (result-of+ (calva/evaluate-code+ (cond-> {:ex/dispatch! dispatch!
                                                 :calva/code code
                                                 :calva/repl-session-key replSessionKey
                                                 :calva/who who
                                                 :calva/description description}
                                          ns (assoc :calva/ns ns)
                                          targetRuntimeId (assoc :calva/target-runtime-id targetRuntimeId)))))))

(defn- list-sessions+
  [{:ex/keys [dispatch!]
    :call/keys [arguments]}]
  (result-of+ (calva/list-sessions+ {:ex/dispatch! dispatch!
                                     :calva/include-all-runtimes? (true? (:includeAllRuntimes arguments))})))

(defn- symbol-info+
  [{:ex/keys [dispatch!]
    :call/keys [arguments]}]
  (let [{:keys [clojureSymbol replSessionKey]
         ns :namespace} arguments]
    (result-of+ (calva/get-symbol-info+ {:ex/dispatch! dispatch!
                                         :calva/clojure-symbol clojureSymbol
                                         :calva/repl-session-key replSessionKey
                                         :calva/ns ns}))))

(defn- clojuredocs+
  [{:ex/keys [dispatch!]
    :call/keys [arguments]}]
  (result-of+ (calva/get-clojuredocs+ {:ex/dispatch! dispatch!
                                       :calva/clojure-symbol (:clojureSymbol arguments)})))

(defn- output-log+
  [{:ex/keys [dispatch!]
    :call/keys [arguments]}]
  (result-of+ (calva/query-output {:ex/dispatch! dispatch!
                                   :calva/query-edn-str (:query arguments)
                                   :calva/inputs (:inputs arguments)})))

(defn- balance-brackets+
  [{:ex/keys [dispatch!]
    :call/keys [arguments]}]
  (result-of+ (balance/infer-parens-response {:ex/dispatch! dispatch!
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
  [{:ex/keys [dispatch!]
    :call/keys [arguments]}]
  (let [{:keys [filePath replSessionKey who]} arguments]
    (if-let [input-error (load-file-input-error arguments)]
      (result-of+ {:error input-error})
      (.then (js/Promise.resolve (calva/load-file+ {:ex/dispatch! dispatch!
                                                    :calva/file-path filePath
                                                    :calva/repl-session-key replSessionKey
                                                    :calva/who who}))
             (fn [{:keys [error] :as data}]
               (if error
                 {:result/data {:error error}
                  :result/error? true}
                 {:result/data data}))))))

(defn- edit-files+
  [{:call/keys [arguments]}]
  (result-of+ (calva/edit-files+ {:calva/edits (:edits arguments)})))

;; The tool table

(def tools
  "One row per tool: the var that runs it, the default for its `maxImages` argument when
   images in its result become image parts, and what a person is shown before it runs."
  (merge-with merge
              confirmation/confirmations
              {"clojure_evaluate_code"    {:tool/function #'evaluate-code+
                                           :tool/default-max-images 10}
               "clojure_list_sessions"    {:tool/function #'list-sessions+}
               "clojure_symbol_info"      {:tool/function #'symbol-info+}
               "clojuredocs_info"         {:tool/function #'clojuredocs+}
               "clojure_repl_output_log"  {:tool/function #'output-log+
                                           :tool/default-max-images 0}
               "clojure_balance_brackets" {:tool/function #'balance-brackets+}
               "clojure_load_file"        {:tool/function #'load-clojure-file+}
               "clojure_edit_files"       {:tool/function #'edit-files+}}))

(defn- content-parts
  [{:tool/keys [default-max-images]} {:keys [maxImages]} data]
  (if default-max-images
    (content/parts data (if (some? maxImages) maxImages default-max-images))
    (content/parts data)))

(defn call-tool+
  "Runs `tool` with `arguments`. Returns a promise of `{:result/content parts}`, with
   `:result/error? true` when the tool failed. The promise rejects when the tool throws."
  [dispatch! tool arguments]
  (let [{:tool/keys [function] :as row} (get tools tool)]
    (-> (js/Promise.resolve nil)
        (.then (fn [_]
                 (function {:ex/dispatch! dispatch!
                            :call/tool tool
                            :call/arguments arguments})))
        (.then (fn [{:result/keys [data error?]}]
                 (cond-> {:result/content (content-parts row arguments data)}
                   error? (assoc :result/error? true)))))))

;; The Language Model API's entry point

(defn- when-clause-settings
  "The settings that the tools' when clauses in package.json name, by clause."
  []
  (let [^js config (vscode/workspace.getConfiguration "calva-backseat-driver")]
    {"config.calva-backseat-driver.provideBdSkill" (.get config "provideBdSkill")
     "config.calva-backseat-driver.provideEditSkill" (.get config "provideEditSkill")
     "config.calva-backseat-driver.enableMcpReplEvaluation" (true? (.get config "enableMcpReplEvaluation"))
     ":calva-mcp-extension/activated?" true}))

(defn- language-model-part
  "Returns the Language Model API's part for a content part. A text part without text,
   from a tool that answered `undefined`, becomes the text `null`."
  [{:keys [type text mimeType data]}]
  (if (= "image" type)
    (vscode/LanguageModelDataPart.image (js/Uint8Array. (js/Buffer.from data "base64")) mimeType)
    (vscode/LanguageModelTextPart. (or text "null"))))

(defn- language-model-result
  [{:result/keys [content]}]
  (vscode/LanguageModelToolResult. (into-array (map language-model-part content))))

(defn- arguments-of [^js options]
  (js->clj (.-input options) :keywordize-keys true))

(defn- language-model-tool
  "Returns the object VS Code calls for `tool`."
  [dispatch! extension-context tool]
  #js {:prepareInvocation (fn [^js options _token]
                            (clj->js (confirmation/prepared-invocation tool (arguments-of options))))
       :invoke (fn [^js options _token]
                 (if (= :allowed (manifest/tool-call-allowed? extension-context tool {:settings (when-clause-settings)}))
                   (.then (call-tool+ dispatch! tool (arguments-of options))
                          language-model-result)
                   (js/Promise.reject (js/Error. "Unknown tool"))))})

(defn register-language-model-tools
  "Registers every tool in the tool table with the Language Model API. Returns the disposables."
  [dispatch! extension-context]
  (mapv (fn [tool]
          (vscode/lm.registerTool tool (language-model-tool dispatch! extension-context tool)))
        (keys tools)))

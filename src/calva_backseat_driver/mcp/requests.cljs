(ns calva-backseat-driver.mcp.requests
  (:require
   ["vscode" :as vscode]
   [calva-backseat-driver.bracket-balance :as bracket-balance]
   [calva-backseat-driver.integrations.calva.features :as calva]
   [clojure.string :as string]
   [promesa.core :as p]))

(defn- get-extension-version []
  (some-> (vscode/extensions.getExtension "betterthantomorrow.calva-backseat-driver")
          .-packageJSON
          .-version))

(defn- ^js tool-manifest [tool-name]
  (try
    (let [extension (vscode/extensions.getExtension "betterthantomorrow.calva-backseat-driver")]
      (if extension
        (let [^js contributes (some-> extension
                                      .-packageJSON
                                      .-contributes)]
          (some->> contributes
                   .-languageModelTools
                   (filter (fn [^js tool]
                             (= tool-name (.-name tool))))
                   first))
        (do
          (js/console.warn "[Server] Extension not found when looking for tool manifest for:" tool-name)
          nil)))
    (catch :default err
      (js/console.error "[Server] Error getting tool manifest for:" tool-name "error:" (.-message err))
      nil)))

(defn- tool-description [tool-name]
  (some-> tool-name
          tool-manifest
          .-modelDescription))

(defn- param-description [tool-name param]
  (some-> (tool-manifest tool-name)
          .-inputSchema
          .-properties
          (unchecked-get param)
          .-description))

(comment
  (tool-description  "clojure_evaluate_code")
  (param-description "clojure_evaluate_code" "code")
  :rcf)

(def evaluate-code-tool-listing
  (let [tool-name "clojure_evaluate_code"]
    {:name tool-name
     :description (tool-description tool-name)
     :inputSchema {:type "object"
                   :properties {"code" {:type "string"
                                        :description (param-description tool-name "code")}
                                "namespace" {:type "string"
                                             :description (param-description tool-name "namespace")}
                                "replSessionKey" {:type "string"
                                                  :description (param-description tool-name "replSessionKey")}}
                   :required ["code" "namespace" "replSessionKey"]
                   :audience ["user" "assistant"]
                   :priority 9}}))

(def list-sessions-tool-listing
  (let [tool-name "clojure_list_sessions"]
    {:name tool-name
     :description (tool-description tool-name)
     :inputSchema {:type "object"
                   :properties {}
                   :required []
                   :audience ["user" "assistant"]
                   :priority 9}}))

(def symbol-info-tool-listing
  (let [tool-name "clojure_symbol_info"]
    {:name tool-name
     :description (tool-description tool-name)
     :inputSchema {:type "object"
                   :properties {"clojureSymbol" {:type "string"
                                                 :description (param-description tool-name "clojureSymbol")}
                                "namespace" {:type "string"
                                             :description (param-description tool-name "namespace")}
                                "replSessionKey" {:type "string"
                                                  :description (param-description tool-name "replSessionKey")}}
                   :required ["clojureSymbol"  "replSessionKey" "namespace"]
                   :audience ["user" "assistant"]
                   :priority 8}}))

(def output-log-tool-info
  (let [tool-name "clojure_repl_output_log"]
    {:name tool-name
     :description (tool-description tool-name)
     :inputSchema {:type "object"
                   :properties {"sinceLine" {:type "integer"
                                             :description (param-description tool-name "sinceLine")}}
                   :required ["sinceLine"]
                   :audience ["user" "assistant"]
                   :priority 10}}))

(def clojuredocs-tool-listing
  (let [tool-name "clojuredocs_info"]
    {:name tool-name
     :description (tool-description tool-name)
     :inputSchema {:type "object"
                   :properties {"clojureSymbol" {:type "string"
                                                 :description (param-description tool-name "clojureSymbol")}}
                   :required ["clojureSymbol"]
                   :audience ["user" "assistant"]
                   :priority 8}}))

(def replace-top-level-form-tool-listing
  (let [tool-name "replace_top_level_form"]
    {:name tool-name
     :description (tool-description tool-name)
     :inputSchema {:type "object"
                   :properties {"filePath" {:type "string"
                                            :description (param-description tool-name "filePath")}
                                "line" {:type "integer"
                                        :description (param-description tool-name "line")}
                                "targetLineText" {:type "string"
                                                  :description (param-description tool-name "targetLineText")}
                                "newForm" {:type "string"
                                           :description (param-description tool-name "newForm")}}
                   :required ["filePath" "line" "targetLineText" "newForm"]
                   :audience ["user" "assistant"]
                   :priority 7}}))

(def insert-top-level-form-tool-listing
  (let [tool-name "insert_top_level_form"]
    {:name tool-name
     :description (tool-description tool-name)
     :inputSchema {:type "object"
                   :properties {"filePath" {:type "string"
                                            :description (param-description tool-name "filePath")}
                                "line" {:type "integer"
                                        :description (param-description tool-name "line")}
                                "targetLineText" {:type "string"
                                                  :description (param-description tool-name "targetLineText")}
                                "newForm" {:type "string"
                                           :description (param-description tool-name "newForm")}}
                   :required ["filePath" "line" "targetLineText" "newForm"]
                   :audience ["user" "assistant"]
                   :priority 7}}))

(def bracket-balance-tool-listing
  (let [tool-name "clojure_balance_brackets"]
    {:name tool-name
     :description (tool-description tool-name)
     :inputSchema {:type "object"
                   :properties {"text" {:type "string"
                                        :description (param-description tool-name "text")}}
                   :required ["text"]
                   :audience ["user" "assistant"]
                   :priority 10}}))

(def structural-create-file-tool-listing
  (let [tool-name "clojure_create_file"]
    {:name tool-name
     :description (tool-description tool-name)
     :inputSchema {:type "object"
                   :properties {"filePath" {:type "string"
                                            :description (param-description tool-name "filePath")}
                                "content" {:type "string"
                                           :description (param-description tool-name "content")}}
                   :required ["filePath" "content"]
                   :audience ["user" "assistant"]
                   :priority 7}}))

(def append-code-tool-listing
  (let [tool-name "clojure_append_code"]
    {:name tool-name
     :description (tool-description tool-name)
     :inputSchema {:type "object"
                   :properties {"filePath" {:type "string"
                                            :description (param-description tool-name "filePath")}
                                "code" {:type "string"
                                        :description (param-description tool-name "code")}}
                   :required ["filePath" "code"]
                   :audience ["user" "assistant"]
                   :priority 7}}))

(defn handle-request-fn [{:ex/keys [dispatch!] :as options
                          :mcp/keys [repl-enabled?]}
                         {:keys [id method params] :as request}]
  (dispatch! [[:app/ax.log :debug "[Server] handle-request " (pr-str request)]])
  (cond
    (= method "initialize")
    (let [response {:jsonrpc "2.0"
                    :id id
                    :result {:serverInfo {:name "calva-backseat-driver"
                                          :version (get-extension-version)}
                             :protocolVersion "2024-11-05"
                             :capabilities {:tools {:listChanged true}
                                            :resources {:listChanged true}}
                             :instructions "Use the `get-output-log` tool to tap into output that gives insight in how the program under development is doing, use the `clojure_evaluate_code` tool (if available) to evaluate Clojure/ClojureScript code. There are also tools for getting symbol info and for getting clojuredocs.org info. The `replace_top_level_form` and `insert_top_level_form` tools enable structural editing of Clojure code."
                             :description "Gives access to the Calva API, including Calva REPL output, the Clojure REPL connection (if this is enabled in settings), Clojure symbol info, clojuredocs.org lookup, and structural editing tools for Clojure code. Effectively turning the AI Agent into a Clojure Interactive Programmer."}}]
      response)

    (= method "tools/list")
    (let [response {:jsonrpc "2.0"
                    :id id
                    :result {:tools (cond-> []
                                      :always
                                      (conj bracket-balance-tool-listing)

                                      (= true repl-enabled?)
                                      (conj evaluate-code-tool-listing)

                                      (calva/exists-list-sessions?)
                                      (conj list-sessions-tool-listing)

                                      (calva/exists-get-symbol-info?)
                                      (conj symbol-info-tool-listing)

                                      (calva/exists-get-clojuredocs?)
                                      (conj clojuredocs-tool-listing)

                                      (calva/exists-on-output?)
                                      (conj output-log-tool-info)

                                      true
                                      (conj replace-top-level-form-tool-listing)

                                      true
                                      (conj insert-top-level-form-tool-listing)

                                      true
                                      (conj structural-create-file-tool-listing)

                                      true
                                      (conj append-code-tool-listing))}}]
      response)

    (= method "resources/templates/list")
    (let [response {:jsonrpc "2.0"
                    :id id
                    :result {:resourceTemplates (cond-> []
                                                  (calva/exists-get-symbol-info?)
                                                  (conj {:uriTemplate "/symbol-info/{symbol}@{session-key}@{namespace}"
                                                         :name "symbol-info"
                                                         :description (tool-description "clojure_symbol_info")
                                                         :mimeType "application/json"})

                                                  (calva/exists-get-clojuredocs?)
                                                  (conj {:uriTemplate "/clojuredocs/{symbol}"
                                                         :name "clojuredocs"
                                                         :description (tool-description "clojuredocs_info")
                                                         :mimeType "application/json"}))}}]
      response)

    (= method "tools/call")
    (let [{:keys [arguments]
           tool :name} params]
      (cond
        (and (= tool "clojure_evaluate_code")
             (= true repl-enabled?))
        (p/let [{:keys [code replSessionKey]
                 ns :namespace} arguments
                result (calva/evaluate-code+ (merge options
                                                    {:calva/code code
                                                     :calva/repl-session-key replSessionKey}
                                                    (when ns
                                                      {:calva/ns ns})))]
          {:jsonrpc "2.0"
           :id id
           :result {:content [{:type "text"
                               :text (js/JSON.stringify result)}]}})

        (= tool "clojure_list_sessions")
        (p/let [result (calva/list-sessions+ options)]
          {:jsonrpc "2.0"
           :id id
           :result {:content [{:type "text"
                               :text (js/JSON.stringify result)}]}})

        (= tool "clojure_symbol_info")
        (p/let [{:keys [clojureSymbol replSessionKey]
                 ns :namespace} arguments
                clojure-docs (calva/get-symbol-info+ (merge options
                                                            {:calva/clojure-symbol clojureSymbol
                                                             :calva/repl-session-key replSessionKey
                                                             :calva/ns ns}))]
          {:jsonrpc "2.0"
           :id id
           :result {:content [{:type "text"
                               :text (js/JSON.stringify clojure-docs)}]}})

        (= tool "clojuredocs_info")
        (p/let [{:keys [clojureSymbol]} arguments
                clojure-docs (calva/get-clojuredocs+ (merge options
                                                            {:calva/clojure-symbol clojureSymbol}))]
          {:jsonrpc "2.0"
           :id id
           :result {:content [{:type "text"
                               :text (js/JSON.stringify clojure-docs)}]}})

        (= tool "clojure_repl_output_log")
        (p/let [{:keys [sinceLine]} arguments
                output (calva/get-output (merge options
                                                {:calva/since-line sinceLine}))]
          {:jsonrpc "2.0"
           :id id
           :result {:content [{:type "text"
                               :text (js/JSON.stringify output)}]}})

        (= tool "clojure_balance_brackets")
        (let [{:keys [text]} arguments
              result (bracket-balance/infer-parens-response (merge options
                                                                   {:calva/text text}))]
          {:jsonrpc "2.0"
           :id id
           :result {:content [{:type "text"
                               :text (js/JSON.stringify result)}]}})

        (= tool "replace_top_level_form")
        (p/let [{:keys [filePath line targetLineText newForm]} arguments
                result (calva/replace-top-level-form+ (merge options
                                                             {:calva/file-path filePath
                                                              :calva/line line
                                                              :calva/target-line-text targetLineText
                                                              :calva/new-form newForm}))]
          {:jsonrpc "2.0"
           :id id
           :result {:content [{:type "text"
                               :text (js/JSON.stringify (clj->js result))}]}})

        (= tool "insert_top_level_form")
        (p/let [{:keys [filePath line targetLineText newForm]} arguments
                result (calva/insert-top-level-form+ (merge options
                                                            {:calva/file-path filePath
                                                             :calva/line line
                                                             :calva/target-line-text targetLineText
                                                             :calva/new-form newForm}))]
          {:jsonrpc "2.0"
           :id id
           :result {:content [{:type "text"
                               :text (js/JSON.stringify (clj->js result))}]}})

        (= tool "clojure_create_file")
        (p/let [{:keys [filePath content]} arguments
                result (calva/structural-create-file+ (merge options
                                                             {:calva/file-path filePath
                                                              :calva/content content}))]
          {:jsonrpc "2.0"
           :id id
           :result {:content [{:type "text"
                               :text (js/JSON.stringify (clj->js result))}]}})

        (= tool "clojure_append_code")
        (p/let [{:keys [filePath code]} arguments
                result (calva/append-code+ (merge options
                                                  {:calva/file-path filePath
                                                   :calva/code code}))]
          {:jsonrpc "2.0"
           :id id
           :result {:content [{:type "text"
                               :text (js/JSON.stringify (clj->js result))}]}})

        :else
        {:jsonrpc "2.0"
         :id id
         :error {:code -32601
                 :message "Unknown tool"}}))

    (= method "resources/read")
    (let [{:keys [uri]} params]
      (cond
        (string/starts-with? uri "/symbol-info/")
        (p/let [[_ clojureSymbol session-key ns] (re-find #"^/symbol-info/([^@]+)@([^@]+)@(.+)$" uri)
                info (calva/get-symbol-info+ (merge options
                                                    {:calva/clojure-symbol clojureSymbol
                                                     :calva/repl-session-key session-key
                                                     :calva/ns ns}))]
          {:jsonrpc "2.0"
           :id id
           :result {:contents [{:uri uri
                                :text (js/JSON.stringify info)}]}})

        (string/starts-with? uri "/clojuredocs/")
        (p/let [[_ clojureSymbol] (re-find #"^/clojuredocs/(.+)$" uri)
                info (calva/get-clojuredocs+ (merge options
                                                    {:calva/clojure-symbol clojureSymbol}))]
          {:jsonrpc "2.0"
           :id id
           :result {:contents [{:uri uri
                                :text (js/JSON.stringify info)}]}})

        :else
        {:jsonrpc "2.0"
         :id id
         :error {:code -32601 :message "Unknown resource URI"}}))

    (= method "ping")
    (let [response {:jsonrpc "2.0"
                    :id id
                    :result {}}]
      response)

    (= method "resources/list")
    (let [response {:jsonrpc "2.0"
                    :id id
                    :result {:resources []}}]
      response)


    id
    {:jsonrpc "2.0" :id id :error {:code -32601 :message "Method not found"}}

    :else ;; returning nil so that the response is not sent
    nil))

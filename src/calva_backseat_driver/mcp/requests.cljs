(ns calva-backseat-driver.mcp.requests
  (:require
   ["vscode" :as vscode]
   [calva-backseat-driver.integrations.calva.api :as calva]
   [calva-backseat-driver.bracket-balance :as bracket-balance]
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
  (tool-description  "evaluate_clojure_code")
  (param-description "evaluate_clojure_code" "code")
  :rcf)

(def evaluate-code-tool-listing
  (let [tool-name "evaluate_clojure_code"]
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

(def symbol-info-tool-listing
  (let [tool-name "get_symbol_info"]
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
  (let [tool-name "get_repl_output_log"]
    {:name tool-name
     :description (tool-description tool-name)
     :inputSchema {:type "object"
                   :properties {"sinceLine" {:type "integer"
                                             :description (param-description tool-name "sinceLine")}}
                   :required ["sinceLine"]
                   :audience ["user" "assistant"]
                   :priority 10}}))

(def clojuredocs-tool-listing
  (let [tool-name "get_clojuredocs_info"]
    {:name tool-name
     :description (tool-description tool-name)
     :inputSchema {:type "object"
                   :properties {"clojureSymbol" {:type "string"
                                                 :description (param-description tool-name "clojureSymbol")}}
                   :required ["clojureSymbol"]
                   :audience ["user" "assistant"]
                   :priority 8}}))

(def bracket-balance-tool-listing
  (let [tool-name "infer_brackets"]
    {:name tool-name
     :description (tool-description tool-name)
     :inputSchema {:type "object"
                   :properties {"text" {:type "string"
                                        :description (param-description tool-name "text")}}
                   :required ["text"]
                   :audience ["user" "assistant"]
                   :priority 10}}))

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
                             :instructions "Use the `get-output-log` tool to tap into output that gives insight in how the program under development is doing, use the `evaluate_clojure_code` tool (if available) to evaluate Clojure/ClojureScript code. There are also tools for getting symbol info and for getting clojuredocs.org info."
                             :description "Gives access to the Calva API, including Calva REPL output, the Clojure REPL connection (if this is enabled in settings), Clojure symbol info, and clojuredocs.org lookup. Effectively turning the AI Agent into a Clojure Interactive Programmer."}}]
      response)

    (= method "tools/list")
    (let [response {:jsonrpc "2.0"
                    :id id
                    :result {:tools (cond-> []
                                      :always
                                      (conj bracket-balance-tool-listing)

                                      (= true repl-enabled?)
                                      (conj evaluate-code-tool-listing)

                                      (calva/exists-get-symbol-info?)
                                      (conj symbol-info-tool-listing)

                                      (calva/exists-get-clojuredocs?)
                                      (conj clojuredocs-tool-listing)

                                      (calva/exists-on-output?)
                                      (conj output-log-tool-info))}}]
      response)

    (= method "resources/templates/list")
    (let [response {:jsonrpc "2.0"
                    :id id
                    :result {:resourceTemplates (cond-> []
                                                  (calva/exists-get-symbol-info?)
                                                  (conj {:uriTemplate "/symbol-info/{symbol}@{session-key}@{namespace}"
                                                         :name "symbol-info"
                                                         :description (tool-description "get_symbol_info")
                                                         :mimeType "application/json"})

                                                  (calva/exists-get-clojuredocs?)
                                                  (conj {:uriTemplate "/clojuredocs/{symbol}"
                                                         :name "clojuredocs"
                                                         :description (tool-description "get_clojuredocs_info")
                                                         :mimeType "application/json"}))}}]
      response)

    (= method "tools/call")
    (let [{:keys [arguments]
           tool :name} params]
      (cond
        (and (= tool "evaluate_clojure_code")
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

        (= tool "get_symbol_info")
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

        (= tool "get_clojuredocs_info")
        (p/let [{:keys [clojureSymbol]} arguments
                clojure-docs (calva/get-clojuredocs+ (merge options
                                                            {:calva/clojure-symbol clojureSymbol}))]
          {:jsonrpc "2.0"
           :id id
           :result {:content [{:type "text"
                               :text (js/JSON.stringify clojure-docs)}]}})

        (= tool "get_repl_output_log")
        (p/let [{:keys [sinceLine]} arguments
                output (calva/get-output (merge options
                                                {:calva/since-line sinceLine}))]
          {:jsonrpc "2.0"
           :id id
           :result {:content [{:type "text"
                               :text (js/JSON.stringify output)}]}})

        (= tool "infer_brackets")
        (let [{:keys [text]} arguments
              result (bracket-balance/infer-parens (merge options
                                                          {:calva/text text}))]
          {:jsonrpc "2.0"
           :id id
           :result {:content [{:type "text"
                               :text (js/JSON.stringify result)}]}})

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

    id
    {:jsonrpc "2.0" :id id :error {:code -32601 :message "Method not found"}}

    :else ;; returning nil so that the response is not sent
    nil))

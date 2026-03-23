(ns calva-backseat-driver.mcp.requests
  (:require
   ["fs" :as fs]
   ["path" :as path]
   ["vscode" :as vscode]
   [calva-backseat-driver.bracket-balance :as bracket-balance]
   [calva-backseat-driver.integrations.calva.features :as calva]
   [calva-backseat-driver.mcp.skills :as skills]
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
                                                  :description (param-description tool-name "replSessionKey")}
                                "who" {:type "string"
                                       :description (param-description tool-name "who")}
                                "description" {:type "string"
                                               :description (param-description tool-name "description")}}
                   :required ["code" "namespace" "replSessionKey" "who"]
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
                                             :description (param-description tool-name "sinceLine")}
                                "includeWho" {:type "array"
                                              :items {:type "string"}
                                              :description (param-description tool-name "includeWho")}
                                "excludeWho" {:type "array"
                                              :items {:type "string"}
                                              :description (param-description tool-name "excludeWho")}}
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

(defn- skill-manifests []
  (try
    (let [extension (vscode/extensions.getExtension "betterthantomorrow.calva-backseat-driver")]
      (if extension
        (let [^js skills (some-> extension
                                 .-packageJSON
                                 .-contributes
                                 .-chatSkills)]
          (when skills
            (->> skills
                 (keep (fn [^js entry]
                         (when-let [[_ skill-name] (re-find #"\./assets/skills/([^/]+)/SKILL\.md" (.-path entry))]
                           {:skill/name skill-name
                            :skill/path (.-path entry)}))))))
        (do
          (js/console.warn "[Server] Extension not found when looking for skill manifests")
          [])))
    (catch :default err
      (js/console.error "[Server] Error getting skill manifests:" (.-message err))
      [])))



(defn- get-skills []
  (let [manifests (skill-manifests)
        extension (vscode/extensions.getExtension "betterthantomorrow.calva-backseat-driver")
        ext-path (when extension (.. extension -extensionUri -fsPath))]
    (when ext-path
      (->> manifests
           (keep (fn [{:skill/keys [name] :as manifest}]
                   (try
                     (let [rel-path (string/replace-first (:skill/path manifest) "./" "")
                           abs-path (path/join ext-path rel-path)
                           content (str (fs/readFileSync abs-path "utf8"))
                           {:keys [description]} (skills/parse-skill-frontmatter content)]
                       {:skill/name name
                        :skill/description (or description "")
                        :skill/uri (str "/skills/" name "/SKILL.md")
                        :skill/content content})
                     (catch :default err
                       (js/console.warn "[Server] Error reading skill" name ":" (.-message err))
                       nil))))
           vec))))



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
                             :instructions (skills/compose-instructions repl-enabled? (get-skills))
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
        (let [{:keys [code replSessionKey who description]
               ns :namespace} arguments
              who-error (calva/validate-who who)]
          (if who-error
            {:jsonrpc "2.0"
             :id id
             :result {:content [{:type "text"
                                 :text (js/JSON.stringify (clj->js {:error who-error}))}]}}
            (p/let [result (calva/evaluate-code+ (merge options
                                                        {:calva/code code
                                                         :calva/repl-session-key replSessionKey
                                                         :calva/who who
                                                         :calva/description description}
                                                        (when ns
                                                          {:calva/ns ns})))]
              {:jsonrpc "2.0"
               :id id
               :result {:content [{:type "text"
                                   :text (js/JSON.stringify result)}]}})))

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
        (let [{:keys [sinceLine includeWho excludeWho]} arguments
              output (calva/get-output (merge options
                                              {:calva/since-line sinceLine
                                               :calva/include-who includeWho
                                               :calva/exclude-who excludeWho}))]
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

        (string/starts-with? uri "/skills/")
        (let [[_ skill-name] (re-find #"^/skills/([^/]+)/SKILL\.md$" uri)
              skill (some #(when (= (:skill/name %) skill-name) %) (get-skills))]
          (if skill
            {:jsonrpc "2.0"
             :id id
             :result {:contents [{:uri uri
                                  :text (:skill/content skill)
                                  :mimeType "text/markdown"}]}}
            {:jsonrpc "2.0"
             :id id
             :error {:code -32602 :message (str "Skill not found: " uri)}}))

        :else
        {:jsonrpc "2.0"
         :id id
         :error {:code -32602 :message "Unknown resource URI"}}))

    (= method "ping")
    (let [response {:jsonrpc "2.0"
                    :id id
                    :result {}}]
      response)

    (= method "resources/list")
    (let [skills (get-skills)
          response {:jsonrpc "2.0"
                    :id id
                    :result {:resources (into []
                                              (map (fn [{:skill/keys [name description uri]}]
                                                     {:uri uri
                                                      :name name
                                                      :description description
                                                      :mimeType "text/markdown"}))
                                              skills)}}]
      response)


    id
    {:jsonrpc "2.0" :id id :error {:code -32601 :message "Method not found"}}

    :else ;; returning nil so that the response is not sent
    nil))



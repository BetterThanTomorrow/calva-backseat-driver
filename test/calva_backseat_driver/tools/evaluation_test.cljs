(ns calva-backseat-driver.tools.evaluation-test
  (:require [cljs.test :refer [deftest testing is]]
            [calva-backseat-driver.tools.evaluation :as evaluation]))

(def ^:private arguments
  {:code "(+ 1 2)" :namespace "user" :replSessionKey "clj" :who "coder"})

(def ^:private sessions
  [{:replSessionKey "clj" :projectRoot "/projects/mini" :replType "clj"}
   {:replSessionKey "cljs" :projectRoot "/projects/mini" :replType "cljs"}])

(def ^:private evaluation
  "What Calva gave for `(+ 1 2)`."
  {:evaluation/result "3"
   :evaluation/ns "user"
   :evaluation/output nil
   :evaluation/error-output nil
   :evaluation/session-key "clj"
   :evaluation/who "coder"})

(deftest input-error
  (is (nil? (evaluation/input-error arguments))
      "A call with a who and balanced code can run")
  (is (= {:result/data {:error "The `who` parameter is required. Provide a slug identifying your agent (e.g. \"copilot\")."}}
         (evaluation/input-error (assoc arguments :who ""))
         (evaluation/input-error (dissoc arguments :who)))
      "A blank who and a missing one")
  (is (= {:result/data {:error "The `who` value \"ui\" is reserved by Calva. Choose a different identifier."}}
         (evaluation/input-error (assoc arguments :who "ui")))
      "A who that Calva reserves")
  (is (= {:result/data {:valid? false
                        :error "Code has unbalanced brackets. The bracket balancer would change the code structure, which could alter its meaning. See the 'balanced-code' field for what the balancer would produce. If that looks correct, please re-run this tool using that code. Otherwise, fix the indentation and bracket balance before re-running"
                        :balanced-code "(+ 1 2)"}
          :result/log [[:debug "[Server] Code was unbalanced:" "(+ 1 2" "balanced-code:" "(+ 1 2)"]]}
         (evaluation/input-error (assoc arguments :code "(+ 1 2")))
      "Unbalanced code gets the balanced code back, and a log line")
  (is (= {:error "The `who` parameter is required. Provide a slug identifying your agent (e.g. \"copilot\")."}
         (:result/data (evaluation/input-error {:code "(+ 1 2" :who ""})))
      "The who is checked before the brackets"))

(deftest session-error
  (is (nil? (evaluation/session-error arguments sessions))
      "A session that Calva has")
  (is (= {:result/data {:valid? false
                        :error "Session 'no-such-session' not found."
                        :available-sessions [{:session-key "clj" :project-root "/projects/mini"}
                                             {:session-key "cljs" :project-root "/projects/mini"}]}}
         (evaluation/session-error (assoc arguments :replSessionKey "no-such-session") sessions))
      "A session that Calva doesn't have gets the ones it has"))

(deftest calva-options
  (is (= {:sessionKey "clj"
          :ns "user"
          :who "coder"
          :nReplOptions {:pprintOptions {:enabled true
                                         :printEngine "pprint"
                                         :maxLength 25
                                         :maxDepth 7}}}
         (evaluation/calva-options arguments {:vscode/config.evaluation.maxLength 25
                                              :vscode/config.evaluation.maxDepth 7}))
      "The print limits come from the settings")
  (is (= {:sessionKey "cljs"
          :description "Adding"
          :targetRuntimeId 83}
         (evaluation/calva-options {:code "1" :replSessionKey "cljs" :description "Adding" :targetRuntimeId 83} {}))
      "Without print limits Calva gets no print options, and what the call leaves out is left out"))

(deftest result
  (testing "an evaluation that succeeds"
    (is (= {:result/data {:result "3" :ns "user" :stdout nil :stderr nil :session-key "clj" :notes [] :who "coder"}}
           (evaluation/result arguments evaluation)))
    (is (= "e2e-out\n"
           (get-in (evaluation/result arguments (assoc evaluation :evaluation/output "e2e-out\n"))
                   [:result/data :stdout]))
        "What it printed is its stdout"))
  (testing "an evaluation that throws"
    (is (= {:result/data {:result "nil"
                          :ns "user"
                          :stdout nil
                          :stderr "boom\n"
                          :session-key "clj"
                          :notes []
                          :who "coder"
                          :error "#error {:message \"boom\"}"
                          :stacktrace nil}}
           (evaluation/result arguments (assoc evaluation
                                               :evaluation/result "nil"
                                               :evaluation/error-output "boom\n"
                                               :evaluation/error "#error {:message \"boom\"}"
                                               :evaluation/stacktrace nil)))))
  (testing "the notes"
    (is (= ["When evaluating without providing a namespace argument the evaluation is performed in the `user` namespace. Most often this is not what you want, and instead you should be evaluating providing the namespace argument. If it is the first time you are using a namespace, evaluate its ns-form first."]
           (get-in (evaluation/result (dissoc arguments :namespace) evaluation) [:result/data :notes]))
        "No namespace")
    (is (= ["Not expecting a empty string as a result? If it is the first time you are using a namespace, evaluate its ns-form in the `user` namespace first."]
           (get-in (evaluation/result arguments (assoc evaluation :evaluation/result "")) [:result/data :notes]))
        "An empty result")
    (let [data (:result/data (evaluation/result arguments (assoc evaluation :evaluation/other-whos ["reviewer" "ui"])))]
      (is (= ["Other evaluators active since your last eval: reviewer, ui. Check the output log."]
             (:notes data))
          "Other evaluators")
      (is (= ["reviewer" "ui"] (:other-whos-since-last data)))))
  (testing "a shadow-cljs evaluation"
    (is (= {:shadow-build ":app" :shadow-runtime-id 42}
           (select-keys (:result/data (evaluation/result arguments (assoc evaluation
                                                                          :evaluation/shadow-build ":app"
                                                                          :evaluation/shadow-runtime-id 42)))
                        [:shadow-build :shadow-runtime-id]))))
  (testing "an evaluation that Calva rejects"
    (is (= {:result/data {:result "nil"
                          :stderr "#object[Error Error: No session]"
                          :notes ["* clj: Evaluating `*e` will give your information about the error.\n   * cljs: Evaluating `(.-stack *e), gives you a stack trace"]}
            :result/log [[:debug "[Server] Evaluation failed:" "No session"]]}
           (evaluation/result arguments {:evaluation/rejected-message "No session"
                                         :evaluation/rejected-printed "#object[Error Error: No session]"})))
    (is (= {:result "nil"
            :error "The who 'api' is reserved"
            :notes ["The `who` value you provided is reserved. Choose a different identifier."]}
           (:result/data (evaluation/result arguments {:evaluation/rejected-message "The who 'api' is reserved"
                                                       :evaluation/rejected-printed "#object[Error]"})))
        "for a reserved who")))

(defn- performer
  "A `perform-effect!` that answers from `answers` by effect name, and notes what it was asked in `!asked`."
  [answers !asked]
  (fn [[effect-name :as fx]]
    (swap! !asked conj fx)
    (js/Promise.resolve (get answers effect-name))))

(deftest ^:async evaluate-code
  (let [!asked (atom [])
        perform-effect! (performer {:calva/fx.list-sessions sessions
                                    :calva/fx.evaluate evaluation}
                                   !asked)
        tool-call (fn [arguments]
                    {:call/tool "clojure_evaluate_code"
                     :call/arguments arguments
                     :call/state {:vscode/config.evaluation.maxLength 25}})]
    (try
      (let [[blank-who unknown-session evaluated]
            (await (js/Promise.all
                    #js [(evaluation/evaluate-code+ perform-effect! (tool-call (assoc arguments :who "")))
                         (evaluation/evaluate-code+ perform-effect! (tool-call (assoc arguments :replSessionKey "nope")))
                         (evaluation/evaluate-code+ perform-effect! (tool-call arguments))]))]
        (is (= {:error "The `who` parameter is required. Provide a slug identifying your agent (e.g. \"copilot\")."}
               (:result/data blank-who)))
        (is (= "Session 'nope' not found."
               (get-in unknown-session [:result/data :error])))
        (is (= {:result "3" :ns "user" :stdout nil :stderr nil :session-key "clj" :notes [] :who "coder"}
               (:result/data evaluated)))
        (is (= [[:calva/fx.list-sessions]
                [:calva/fx.list-sessions]
                [:calva/fx.evaluate "(+ 1 2)" {:sessionKey "clj"
                                               :ns "user"
                                               :who "coder"
                                               :nReplOptions {:pprintOptions {:enabled true
                                                                              :printEngine "pprint"
                                                                              :maxLength 25
                                                                              :maxDepth nil}}}]]
               @!asked)
            "A call with a bad who asks Calva nothing, one with an unknown session asks for the sessions, and one that can run evaluates"))
      (catch :default e
        (is (nil? e) "The calls resolve")))))

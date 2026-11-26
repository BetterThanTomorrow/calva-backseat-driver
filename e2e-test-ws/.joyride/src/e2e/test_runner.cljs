(ns e2e.test-runner
  (:require [clojure.string :as string]
            [cljs.test]
            [e2e.baldr]
            [e2e.db :as db]
            ["vscode" :as vscode]
            workspace-activate))

(defn- write [& xs]
  (js/process.stdout.write (string/join " " xs)))

(defn- writeln [& xs]
  (apply write xs)
  (js/process.stdout.write "\n"))

(def old-pass (get-method cljs.test/report [:cljs.test/default :pass]))

(defmethod cljs.test/report [:cljs.test/default :pass] [m]
  (binding [*print-fn* writeln] (old-pass m))
  (swap! db/!state update :pass inc))

(def old-fail (get-method cljs.test/report [:cljs.test/default :fail]))

(defmethod cljs.test/report [:cljs.test/default :fail] [m]
  (binding [*print-fn* writeln] (old-fail m))
  (swap! db/!state update :fail inc))

(def old-error (get-method cljs.test/report [:cljs.test/default :error]))

(defmethod cljs.test/report [:cljs.test/default :error] [m]
  (binding [*print-fn* writeln] (old-error m))
  (swap! db/!state update :error inc))

(def old-end-run-tests (get-method cljs.test/report [:cljs.test/default :end-run-tests]))

(defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
  (binding [*print-fn* write]
    (old-end-run-tests m)
    (let [{:keys [running pass fail error]} @db/!state
          passed-minimum-threshold 2
          fail-reason (cond
                        (< 0 (+ fail error)) "FAILURE: Some tests failed or errored"
                        (< pass passed-minimum-threshold) (str "FAILURE: Less than " passed-minimum-threshold " assertions passed")
                        :else nil)]
      (writeln (str "Runner: tests run, results: {:pass " pass ", :fail " fail ", :error " error "}"))
      (if fail-reason
        ((:reject running) fail-reason)
        ((:resolve running) true)))))

(defn- file->ns [src-path file]
  (-> file
      (subs (inc (count src-path)))
      (string/replace #"\.cljs$" "")
      (string/replace #"/" ".")
      (string/replace #"_" "-")))

(defn- ^:async find-test-nss+ [src-path]
  (let [file-uris (await (vscode/workspace.findFiles (str src-path "/**/*_test.cljs")))
        files (.map file-uris (fn [uri]
                                (vscode/workspace.asRelativePath uri false)))
        nss-strings (-> files
                        (.map (partial file->ns src-path))
                        sort)]
    (mapv symbol nss-strings)))

(defn- ^:async run-when-ws-activated [nss-syms tries]
  (if (:ws-activated? @db/!state)
    (try
      (println "Running tests in" nss-syms)
      (apply require nss-syms)
      (apply cljs.test/run-tests nss-syms)
      (await (:promise (:running @db/!state)))
      (catch :default e
        (workspace-activate/clean-up!+)
        ((:reject (:running @db/!state)) e)))
    (do
      (println "Runner: Workspace not activated yet, tries: " tries "- trying again in a jiffy")
      (js/setTimeout #(run-when-ws-activated nss-syms (inc tries)) 10)
      (:promise (:running @db/!state)))))

(defn- running-promise
  "A promise for the test run, with the functions that resolve and reject it."
  []
  (let [!settle (atom nil)
        promise (js/Promise. (fn [resolve reject]
                               (reset! !settle {:resolve resolve :reject reject})))]
    (assoc @!settle :promise promise)))

(defn ^:export run-all-tests [src-path]
  (let [running (running-promise)]
    (swap! db/!state assoc :running running)
    ((^:async fn []
       (let [nss-syms (await (find-test-nss+ src-path))
             p (await (run-when-ws-activated nss-syms 1))]
         (workspace-activate/clean-up!+)
         (swap! db/!state merge db/default-db)
         p)))
    (:promise running)))


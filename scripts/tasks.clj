(ns tasks
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [babashka.fs :as fs]
            publish
            util))

(def vsix-test-workspace "./e2e-test-vsix")

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn publish! [args]
  (publish/yolo! args))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn print-release-notes! [{:keys [version]}]
  (let [changelog-text (publish/get-changelog-text-for-version version)]
    (println changelog-text)))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn bump-version! [{:keys [bump-branch user-email user-name dry force]}]
  (if force
    (do
      (println "Bumping version")
      (util/shell dry "git" "config" "--global" "user.email" user-email)
      (util/shell dry "git" "config" "--global" "user.name" user-name)
      (util/shell dry "npm" "version" "--no-git-tag-version" "patch")
      (util/shell dry "git" "add" ".")
      (let [version (-> (util/sh false "node" "-p" "require('./package').version")
                        :out
                        string/trim)
            branch (string/replace bump-branch #"^remotes/origin/" "")]
        (util/shell dry "git" "commit" "-m" (str "Bring on version " version "!"))
        (util/shell dry "git" "push" "origin" (str "HEAD:refs/heads/" branch))))
    (println "Use --force to actually bump the version")))

(def ^:private e2e-test-ws-dir "e2e-test-ws")

(def ^:private e2e-tmp-dir ".tmp/e2e-test-ws")

(def ^:private e2e-output-log ".tmp/e2e-output.log")

(def ^:private spinner-frames ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(defn- with-spinner
  "Run f while displaying an animated spinner with message.
   Clears the spinner line when done."
  [message f]
  (let [stop? (atom false)
        spinner-thread (Thread.
                        (fn []
                          (loop [i 0]
                            (when-not @stop?
                              (print (str "\r" (nth spinner-frames (mod i (count spinner-frames))) " " message))
                              (flush)
                              (Thread/sleep 80)
                              (recur (inc i))))))]
    (.start spinner-thread)
    (try
      (f)
      (finally
        (reset! stop? true)
        (.join spinner-thread 200)
        (print (str "\r" (apply str (repeat (+ 3 (count message)) " ")) "\r"))
        (flush)))))

(defn- parse-test-counts
  "Parse structured test results from the Runner summary line."
  [log-file]
  (let [content (slurp log-file)]
    (if-let [[_ pass fail error] (re-find #"Runner: tests run, results: \{:pass (\d+),?\s*:fail (\d+),?\s*:error (\d+)\}" content)]
      (let [passed (parse-long pass)
            failed (+ (parse-long fail) (parse-long error))]
        {:passed passed :failed failed :total (+ passed failed)})
      {:passed 0 :failed 0 :total 0 :warning "Could not find Runner summary in log"})))

(defn- run-e2e-launch!
  "Run e2e tests via launch.js with output redirected to log file.
   Shows spinner during execution, prints brief summary when done."
  [& args]
  (fs/create-dirs (fs/parent e2e-output-log))
  (println "Output:" e2e-output-log)
  (let [exit-code (with-open [writer (io/writer (io/file e2e-output-log))]
                    (with-spinner "Running e2e tests..."
                      #(:exit @(p/process (into ["node" "./e2e-test-ws/launch.js"] args)
                                          {:out writer :err writer}))))
        {:keys [passed failed total warning]} (parse-test-counts e2e-output-log)]
    (println)
    (when warning (println (str "WARNING: " warning)))
    (println (format "Tests: %d/%d passed" passed total))
    (if (zero? exit-code)
      (println "Status: ALL TESTS PASSED")
      (do
        (println (format "Status: TESTS FAILED (%d failed, exit code %d)" failed exit-code))
        (throw (ex-info "E2E tests failed" {:babashka/exit exit-code}))))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn run-e2e-tests-with-vsix! [{:keys [vsix calva-vsix]}]
  (println "Running end-to-end tests using vsix:" vsix)
  (apply util/shell false "node" "./e2e-test-ws/launch.js" (str "--vsix=" vsix)
         (when calva-vsix [(str "--calva-vsix=" calva-vsix)])))

(defn- prepare-tmp-test-workspace! []
  (println "Preparing temporary test workspace...")
  (when (fs/exists? e2e-tmp-dir)
    (fs/delete-tree e2e-tmp-dir))
  (fs/create-dirs e2e-tmp-dir)
  (doseq [item [".joyride" ".vscode" "deps.edn" "test_load_target.clj"]]
    (let [src (fs/path e2e-test-ws-dir item)]
      (if (fs/directory? src)
        (fs/copy-tree src (fs/path e2e-tmp-dir item))
        (fs/copy src (fs/path e2e-tmp-dir item)))))
  e2e-tmp-dir)

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn run-e2e-tests-from-working-dir! [{:keys [is-ci calva-vsix]}]
  (println "Running end-to-end tests using working directory")
  (if is-ci
    (util/shell false "node" "./e2e-test-ws/launch.js")
    (let [tmp-ws (prepare-tmp-test-workspace!)]
      (println "Using temporary test workspace:" tmp-ws)
      (apply run-e2e-launch! (str "--test-workspace=" tmp-ws)
             (when calva-vsix [(str "--calva-vsix=" calva-vsix)])))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn package-pre-release! [{:keys [slug dry]}]
  (let [current-version (-> (util/sh false "node" "-p" "require('./package').version")
                            :out string/trim)
        slug (or slug (-> (util/sh false "git" "rev-parse" "--abbrev-ref" "HEAD")
                          :out string/trim))
        commit-id (-> (util/sh false "git" "rev-parse" "--short" "HEAD")
                      :out string/trim)
        random-slug (util/random-slug 2)
        slugged-branch (string/replace slug #"/" "-")
        version (str current-version "-" slugged-branch "-" commit-id "-" random-slug)
        package-name "calva-backseat-driver"
        vsix-file (str package-name "-" version ".vsix")]
    (println "Current version:" current-version)
    (println "HEAD Commit ID:" commit-id)
    (println "Packaging pre-release...")
    (util/shell dry "npm" "version" "--no-git-tag-version" version)
    (util/shell dry "npx" "vsce" "package" "--pre-release")
    (util/shell dry "npm" "version" "--no-git-tag-version" current-version)
    {:vsix-file vsix-file}))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn package-and-test-prerelease! [{:keys [slug dry]}]
  (let [opts {:slug slug :dry dry}
        vsix-result (package-pre-release! opts)
        vsix-file (:vsix-file vsix-result)]
    (println "Created VSIX:" vsix-file)
    (println "Running e2e tests on the packaged VSIX...")
    (run-e2e-tests-with-vsix! {:vsix vsix-file})))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn launch-with-vsix! [{:keys [vsix]}]
  (println "Uninstalling any existing Calva Backseat Driver extension...")
  (util/shell false "code-insiders" "--uninstall-extension" "betterthantomorrow.calva-backseat-driver")

  (println "Installing VSIX:" vsix)
  (util/shell false "code-insiders" "--install-extension" vsix)

  (println "Launching VS Code Insiders with test workspace...")
  (util/shell false "code-insiders" vsix-test-workspace))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn run-mcp-inspector! [{:keys [vsix port-file]}]
  (let [server-script (if vsix
                        (let [vsix-basename (fs/strip-ext (fs/file-name vsix))
                              extension-path (str (fs/home)
                                                  "/.vscode-insiders/extensions/betterthantomorrow."
                                                  vsix-basename)]
                          (println "Using installed extension at:" extension-path)
                          (fs/path extension-path "dist" "calva-mcp-server.js"))
                        (do
                          (println "Using local dev build")
                          (fs/path "dist" "calva-mcp-server.js")))
        port-file (or port-file
                      (fs/path "test-projects" "example" ".calva" "mcp-server" "port"))]

    (println "Server script:" (str server-script))
    (println "Port file:" (str port-file))

    (util/shell false "npx" "@modelcontextprotocol/inspector" "node" (str server-script) (str port-file))))

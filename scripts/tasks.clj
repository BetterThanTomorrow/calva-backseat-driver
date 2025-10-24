(ns tasks
  (:require #_[babashka.process :as p]
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

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn run-e2e-tests-with-vsix! [{:keys [vsix]}]
  (println "Running end-to-end tests using vsix:" vsix)
  (util/shell false "node" "./e2e-test-ws/launch.js" (str "--vsix=" vsix)))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn run-e2e-tests-from-working-dir! []
  (println "Running end-to-end tests using working directory")
  (util/shell false "node" "./e2e-test-ws/launch.js"))

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
(defn run-mcp-inspector! [{:keys [vsix]}]
  (let [vsix-basename (fs/strip-ext (fs/file-name vsix))
        extension-path (str (fs/home)
                            "/.vscode-insiders/extensions/betterthantomorrow."
                            vsix-basename)
        server-script (fs/path extension-path "dist" "calva-mcp-server.js")
        port-file (fs/path vsix-test-workspace ".calva" "mcp-server" "port")]

    (println "Using installed extension at:" extension-path)
    (println "Server script:" server-script)
    (println "Port file:" port-file)

    (util/shell false "npx" "@modelcontextprotocol/inspector" "node" (str server-script) (str port-file))))

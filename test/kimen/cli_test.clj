(ns kimen.cli-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [kimen.cli :as cli]
    [kimen.exit-code :as exit-code]))

(defn- run-cli
  ([argv files]
   (run-cli argv files {}))
  ([argv files ctx]
   (cli/run (merge {:read-file (fn [path]
                                 (if (contains? files path)
                                   (get files path)
                                   (slurp path)))}
                   ctx)
            argv)))

(defn- with-system-property
  [k v f]
  (let [prev (System/getProperty k)]
    (try
      (System/setProperty k v)
      (f)
      (finally
        (if (some? prev)
          (System/setProperty k prev)
          (System/clearProperty k))))))

(deftest version-json-shape
  (let [{:keys [exit-code stdout stderr]} (run-cli ["version" "--json"] {})]
    (is (= 0 exit-code))
    (is (nil? stderr))
    (is (str/includes? stdout "\"action\":\"version\""))
    (is (str/includes? stdout "\"exit_code\":0"))))

(deftest map-lint-json-ok-and-fail
  (testing "ok"
    (let [map-src "env API_KEY=api_key\nfile conf/api.txt=api_key\n"
          {:keys [exit-code stdout]} (run-cli ["map" "lint" "--map" "good.kmap" "--json"]
                                             {"good.kmap" map-src})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"action\":\"map_lint\""))
      (is (str/includes? stdout "\"ok\":true"))))

  (testing "invalid input"
    (let [{:keys [exit-code stdout]} (run-cli ["map" "lint" "--json"] {})]
      (is (= exit-code/code-map-lint-failed exit-code))
      (is (str/includes? stdout "\"code\":\"invalid_input\""))
      (is (str/includes? stdout "\"ok\":false")))))

(deftest map-lint-warnings-empty-map-and-strict-mode
  (testing "warnings-only maps pass by default"
    (let [map-src (str "env API_KEY=exec:echo \"token\"\n"
                       "file conf/api.txt=const:token\n"
                       "envpath API_KEY_PATH=conf/api.txt\n"
                       "stdin exec:echo \"stdin-token\"\n")
          {:keys [exit-code stdout]} (run-cli ["map" "lint" "--map" "warn.kmap" "--json"]
                                             {"warn.kmap" map-src})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"ok\":true"))
      (is (str/includes? stdout "\"warning_count\":"))
      (is (str/includes? stdout "\"code\":\"exec_source_may_require_wrapper\""))))

  (testing "strict mode turns warnings into failures"
    (let [map-src "env API_KEY=exec:echo \"token\"\n"
          {:keys [exit-code stdout]} (run-cli ["map" "lint" "--map" "warn.kmap" "--strict" "--json"]
                                             {"warn.kmap" map-src})]
      (is (= exit-code/code-map-lint-failed exit-code))
      (is (str/includes? stdout "\"ok\":false"))))

  (testing "empty map is an error"
    (let [{:keys [exit-code stdout]} (run-cli ["map" "lint" "--map" "empty.kmap" "--json"]
                                             {"empty.kmap" "# blank\n"})]
      (is (= exit-code/code-map-lint-failed exit-code))
      (is (str/includes? stdout "\"ok\":false"))
      (is (str/includes? stdout "\"code\":\"empty_map\"")))))

(deftest map-lint-distinguishes-conflicting-and-redundant-duplicates
  (let [map-src (str "env API_KEY=first\n"
                     "env API_KEY=first\n"
                     "env API_KEY=second\n")
        {:keys [exit-code stdout]} (run-cli ["map" "lint" "--map" "dup.kmap" "--json"]
                                           {"dup.kmap" map-src})]
    (is (= exit-code/code-map-lint-failed exit-code))
    (is (str/includes? stdout "\"code\":\"duplicate_env_var\""))
    (is (str/includes? stdout "\"code\":\"redundant_env_var\""))))

(deftest plan-json-success-and-errors
  (testing "success"
    (let [map-src (str "env API_KEY=api_key\n"
                       "file conf/api.txt=api_key\n"
                       "envpath API_KEY_PATH=conf/api.txt\n")
          {:keys [exit-code stdout stderr]} (run-cli ["plan" "--map" "dev.kmap" "--json" "--" "echo" "hi"]
                                                     {"dev.kmap" map-src})]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (str/includes? stdout "\"action\":\"plan\""))
      (is (str/includes? stdout "\"mode\":\"run\""))
      (is (str/includes? stdout "\"command\":[\"echo\",\"hi\"]"))))

  (testing "invalid mode"
    (let [map-src "env API_KEY=api_key\n"
          {:keys [exit-code stderr]} (run-cli ["plan" "--map" "dev.kmap" "--mode" "bad" "--json"]
                                              {"dev.kmap" map-src})]
      (is (= exit-code/code-plan-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"invalid_mode\""))))

  (testing "envpath requires file projection"
    (let [map-src "envpath API_KEY_PATH=conf/api.txt\n"
          {:keys [exit-code stderr]} (run-cli ["plan" "--map" "dev.kmap" "--json"]
                                              {"dev.kmap" map-src})]
      (is (= exit-code/code-plan-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"envpath_requires_projected_files\"")))))

(deftest unknown-command-exits-1
  (let [{:keys [exit-code stderr]} (run-cli ["nope"] {})]
    (is (= 1 exit-code))
    (is (str/includes? stderr "unknown command"))))

(deftest config-path-and-vault-set-show-clear
  (let [cfg-path (.getPath (java.io.File/createTempFile "kimen-config" ".json"))]
    (.delete (java.io.File. cfg-path))

    (let [{:keys [exit-code stdout]} (run-cli ["config" "path" "--json"] {} {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"action\":\"config_path\""))
      (is (str/includes? stdout cfg-path)))

    (let [{:keys [exit-code stdout]} (run-cli ["config" "vault" "set" "/tmp/vault.db" "--json"] {} {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"action\":\"config_vault_set\"")))

    (let [{:keys [exit-code stdout]} (run-cli ["config" "vault" "show" "--json"] {} {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"path\":\"/tmp/vault.db\"")))

    (let [{:keys [exit-code stdout]} (run-cli ["config" "vault" "clear" "--json"] {} {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"action\":\"config_vault_clear\"")))

    (let [{:keys [exit-code stdout]} (run-cli ["config" "vault" "show" "--json"] {} {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"action\":\"config_vault_show\"")))))

(deftest config-unlock-set-show-clear
  (let [cfg-path (.getPath (java.io.File/createTempFile "kimen-config" ".json"))]
    (.delete (java.io.File. cfg-path))

    (let [{:keys [exit-code stdout]} (run-cli ["config" "unlock" "set" "exec" "--json" "--" "op" "read" "secret"] {}
                                              {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"action\":\"config_unlock_set\""))
      (is (str/includes? stdout "\"method\":\"exec\"")))

    (let [{:keys [exit-code stdout]} (run-cli ["config" "unlock" "show" "--json"] {} {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"method\":\"exec\""))
      (is (str/includes? stdout "\"exec\":[\"op\",\"read\",\"secret\"]")))

    (let [{:keys [exit-code stdout]} (run-cli ["config" "unlock" "clear" "--json"] {} {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"action\":\"config_unlock_clear\"")))

    (let [{:keys [exit-code stdout]} (run-cli ["config" "unlock" "show" "--json"] {} {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"method\":\"prompt\"")))))

(deftest config-errors-use-config-exit-code-and-reason
  (let [cfg-path (.getPath (java.io.File/createTempFile "kimen-config" ".json"))]
    (.delete (java.io.File. cfg-path))

    (let [{:keys [exit-code stderr]} (run-cli ["config" "unlock" "set" "exec" "--json"] {} {:config-path cfg-path})]
      (is (= exit-code/code-config-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"missing_unlock_exec_command\"")))

    (let [{:keys [exit-code stderr]} (run-cli ["config" "unlock" "set" "bad-method" "--json"] {} {:config-path cfg-path})]
      (is (= exit-code/code-config-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"unknown_unlock_method\"")))))

(deftest vault-and-secret-lifecycle
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        vault-path (str (.getPath dir) "/vault.db")
        cfg-path (str (.getPath dir) "/config.json")
        pass-cmd "printf test-passphrase"]
    (let [{:keys [exit-code stdout]} (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"action\":\"vault_init\"")))

    (let [{:keys [exit-code stdout]} (run-cli ["vault" "info" "--vault" vault-path "--json"] {} {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"action\":\"vault_info\""))
      (is (str/includes? stdout "\"format_version\":\"kimen-v2\"")))

    (let [{:keys [exit-code stdout]} (run-cli ["secret" "set" "api_key" "--value" "shh" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                                              {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"action\":\"set\"")))

    (let [{:keys [exit-code stdout]} (run-cli ["secret" "list" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                                              {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"action\":\"list\""))
      (is (str/includes? stdout "\"names\":[\"api_key\"]")))

    (let [{:keys [exit-code stdout]} (run-cli ["secret" "get" "api_key" "--unsafe-stdout" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                                              {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"action\":\"get\""))
      (is (str/includes? stdout "\"encoding\":\"base64\""))
      (is (str/includes? stdout "\"value_b64\":\"c2ho\"")))

    (let [{:keys [exit-code stdout]} (run-cli ["secret" "mv" "api_key" "api_key2" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                                              {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"action\":\"mv\"")))

    (let [{:keys [exit-code stdout]} (run-cli ["secret" "rm" "api_key2" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                                              {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"action\":\"rm\"")))

    (let [{:keys [exit-code stdout]} (run-cli ["secret" "list" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                                              {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"names\":[]")))))

(deftest secret-errors-typed-exit-codes
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        vault-path (str (.getPath dir) "/vault.db")
        cfg-path (str (.getPath dir) "/config.json")
        pass-cmd "printf test-passphrase"]
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})

    (let [{:keys [exit-code stderr]} (run-cli ["secret" "get" "missing" "--unsafe-stdout" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                                              {:config-path cfg-path})]
      (is (= exit-code/code-secret-not-found exit-code))
      (is (str/includes? stderr "\"reason\":\"secret_not_found\"")))

    (let [{:keys [exit-code stderr]} (run-cli ["secret" "list" "--vault" vault-path "--passphrase-cmd" "printf wrong" "--json"] {}
                                              {:config-path cfg-path})]
      (is (= exit-code/code-wrong-passphrase exit-code))
      (is (str/includes? stderr "\"reason\":\"wrong_passphrase\"")))))

(deftest run-render-envfile-happy-path
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        vault-path (str (.getPath dir) "/vault.db")
        cfg-path (str (.getPath dir) "/config.json")
        map-path (str (.getPath dir) "/dev.kmap")
        out-dir (str (.getPath dir) "/render")
        envfile-path (str (.getPath dir) "/app.env")
        pass-cmd "printf test-passphrase"
        map-src (str "env API_KEY=api_key\n"
                     "file conf/api.txt=api_key\n"
                     "envpath API_KEY_PATH=conf/api.txt\n")]
    (spit map-path map-src)

    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (run-cli ["secret" "set" "api_key" "--value" "shh" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})

    (let [{:keys [exit-code stdout]} (run-cli ["run" "--map" map-path "--json" "--dry-run" "--" "echo" "ok"] {} {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"action\":\"plan\"")))

    (let [{:keys [exit-code stdout]} (run-cli ["render" "--map" map-path "--dir" out-dir "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                                              {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"action\":\"render\""))
      (is (= "shh" (slurp (str out-dir "/conf/api.txt")))))

    (let [{:keys [exit-code stdout]} (run-cli ["envfile" "--map" map-path "--out" envfile-path "--files-dir" out-dir "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                                              {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"action\":\"envfile\""))
      (let [body (slurp envfile-path)]
        (is (str/includes? body "API_KEY=shh"))
        (is (str/includes? body (str "API_KEY_PATH=" out-dir "/conf/api.txt")))))))

(deftest profile-inputs-work-and-errors-are-typed
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        home-dir (str (.getPath dir) "/home")
        profile-dir (str home-dir "/.config/kimen/profiles")
        profile-path (str profile-dir "/linje-prod.kmap")
        map-src "env API_KEY=api_key\n"]
    (.mkdirs (java.io.File. profile-dir))
    (spit profile-path map-src)

    (with-system-property
      "user.home"
      home-dir
      (fn []
        (let [{:keys [exit-code stdout stderr]} (run-cli ["map" "lint" "--profile" "linje-prod" "--json"] {})]
          (is (= 0 exit-code))
          (is (nil? stderr))
          (is (str/includes? stdout "\"action\":\"map_lint\""))
          (is (str/includes? stdout "\"ok\":true")))

        (let [{:keys [exit-code stdout stderr]} (run-cli ["plan" "--profile" "linje-prod" "--json" "--" "echo" "hi"] {})]
          (is (= 0 exit-code))
          (is (nil? stderr))
          (is (str/includes? stdout "\"action\":\"plan\""))
          (is (str/includes? stdout "\"command\":[\"echo\",\"hi\"]"))))))

  (let [{:keys [exit-code stderr]} (run-cli ["plan" "--profile" "../bad" "--json"] {})]
    (is (= exit-code/code-plan-failed exit-code))
    (is (str/includes? stderr "\"reason\":\"invalid_profile_name\""))))

(deftest map-and-profile-conflicts-are-reported-per-command
  (let [out-dir (str (.getPath (java.io.File/createTempFile "kimen-render" ".tmp")) "-dir")
        out-path (str (.getPath (java.io.File/createTempFile "kimen-envfile" ".tmp")) "-env")]
    (let [{:keys [exit-code stderr]} (run-cli ["plan" "--map" "a.kmap" "--profile" "dev" "--json"] {})]
      (is (= exit-code/code-plan-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"conflicting_map_profile_inputs\"")))

    (let [{:keys [exit-code stderr]} (run-cli ["run" "--map" "a.kmap" "--profile" "dev" "--json" "--dry-run" "--" "echo" "ok"] {})]
      (is (= exit-code/code-projection-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"conflicting_map_profile_inputs\"")))

    (let [{:keys [exit-code stderr]} (run-cli ["render" "--map" "a.kmap" "--profile" "dev" "--dir" out-dir "--json"] {})]
      (is (= exit-code/code-projection-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"conflicting_map_profile_inputs\"")))

    (let [{:keys [exit-code stderr]} (run-cli ["envfile" "--map" "a.kmap" "--profile" "dev" "--out" out-path "--json"] {})]
      (is (= exit-code/code-envfile-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"conflicting_map_profile_inputs\"")))))

(deftest project-plan-alias-matches-plan-command
  (let [map-src "env API_KEY=api_key\n"
        {:keys [exit-code stdout stderr]} (run-cli ["project" "plan" "--map" "dev.kmap" "--json" "--" "echo" "hello"]
                                                   {"dev.kmap" map-src})]
    (is (= 0 exit-code))
    (is (nil? stderr))
    (is (str/includes? stdout "\"action\":\"plan\""))
    (is (str/includes? stdout "\"command\":[\"echo\",\"hello\"]"))))

(deftest project-run-and-render-aliases-work
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        vault-path (str (.getPath dir) "/vault.db")
        cfg-path (str (.getPath dir) "/config.json")
        out-dir (str (.getPath dir) "/render")
        pass-cmd "printf test-passphrase"]
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})

    (let [{:keys [exit-code stdout stderr]}
          (run-cli ["project" "run" "--json" "--dry-run" "--env" "API_KEY=const:shh" "--" "echo" "ok"]
                   {}
                   {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (str/includes? stdout "\"action\":\"plan\"")))

    (let [{:keys [exit-code stdout stderr]}
          (run-cli ["project" "render" "--file" "conf/api.txt=const:shh" "--dir" out-dir "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"]
                   {}
                   {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (str/includes? stdout "\"action\":\"render\""))
      (is (= "shh" (slurp (str out-dir "/conf/api.txt")))))))

(deftest inline-mappings-work-without-map
  (let [{:keys [exit-code stdout stderr]}
        (run-cli ["plan"
                  "--json"
                  "--env" "API_KEY=const:shh"
                  "--file" "conf/api.txt=const:file-val"
                  "--envpath" "API_KEY_PATH=conf/api.txt"
                  "--stdin" "const:stdin-val"
                  "--"
                  "echo"
                  "ok"]
                 {})]
    (is (= 0 exit-code))
    (is (nil? stderr))
    (is (str/includes? stdout "\"action\":\"plan\""))
    (is (str/includes? stdout "\"source\":\"const:shh\""))
    (is (str/includes? stdout "\"path\":\"conf/api.txt\""))
    (is (str/includes? stdout "\"stdin\":\"const:stdin-val\""))))

(deftest run-render-envfile-accept-inline-mappings
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        vault-path (str (.getPath dir) "/vault.db")
        cfg-path (str (.getPath dir) "/config.json")
        out-dir (str (.getPath dir) "/render")
        envfile-path (str (.getPath dir) "/app.env")
        pass-cmd "printf test-passphrase"]
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})

    (let [{:keys [exit-code stdout]}
          (run-cli ["run" "--json" "--dry-run" "--env" "API_KEY=const:shh" "--" "echo" "ok"]
                   {}
                   {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"action\":\"plan\"")))

    (let [{:keys [exit-code stdout]}
          (run-cli ["render" "--file" "conf/api.txt=const:shh" "--dir" out-dir "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"]
                   {}
                   {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"action\":\"render\""))
      (is (= "shh" (slurp (str out-dir "/conf/api.txt")))))

    (let [{:keys [exit-code stdout]}
          (run-cli ["envfile" "--env" "API_KEY=const:shh" "--out" envfile-path "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"]
                   {}
                   {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"action\":\"envfile\""))
      (is (str/includes? (slurp envfile-path) "API_KEY=shh")))))

(deftest stdin-mapping-is-rejected-for-render-and-envfile
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        vault-path (str (.getPath dir) "/vault.db")
        cfg-path (str (.getPath dir) "/config.json")
        map-path (str (.getPath dir) "/dev.kmap")
        out-dir (str (.getPath dir) "/render")
        out-path (str (.getPath dir) "/app.env")
        pass-cmd "printf test-passphrase"]
    (spit map-path "stdin const:hello\nfile conf/api.txt=const:shh\nenv API_KEY=const:shh\n")
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})

    (let [{:keys [exit-code stderr]}
          (run-cli ["render" "--map" map-path "--dir" out-dir "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"]
                   {}
                   {:config-path cfg-path})]
      (is (= exit-code/code-projection-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"stdin_not_supported\"")))

    (let [{:keys [exit-code stderr]}
          (run-cli ["envfile" "--map" map-path "--out" out-path "--files-dir" out-dir "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"]
                   {}
                   {:config-path cfg-path})]
      (is (= exit-code/code-envfile-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"stdin_not_supported\"")))))

(ns kimen.cli-test
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as sh]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [kimen.cli :as cli]
   [kimen.commands.init :as init]
   [kimen.exit-code :as exit-code]
   [kimen.json :as json]))

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

(defn- normalize-newlines
  [s]
  (str/replace (or s "") "\r\n" "\n"))

(def ^:private git-env-skip-vars
  #{"GIT_DIR"
    "GIT_WORK_TREE"
    "GIT_INDEX_FILE"
    "GIT_PREFIX"
    "GIT_OBJECT_DIRECTORY"
    "GIT_ALTERNATE_OBJECT_DIRECTORIES"
    "GIT_COMMON_DIR"})

(defn- sanitized-git-env
  []
  (into {}
        (remove (fn [[k _]] (contains? git-env-skip-vars k)))
        (System/getenv)))

(defn- git-available?
  []
  (zero? (:exit (sh/sh "git" "--version" :out :string :err :string))))

(defn- run-git!
  [dir & args]
  (let [dir (some-> dir str/trim not-empty)
        res (apply sh/sh
                   (concat ["git"]
                           args
                           (cond-> [:env (sanitized-git-env)
                                    :out :string
                                    :err :string]
                             (some? dir) (into [:dir dir]))))]
    (when-not (zero? (:exit res))
      (throw (ex-info (format "git %s failed: %s"
                              (str/join " " args)
                              (or (not-empty (str/trim (str (:err res))))
                                  (not-empty (str/trim (str (:out res))))
                                  "unknown error"))
                      {:args args
                       :res res})))
    res))

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

(deftest plan-supports-against-diff-and-validates-against-inputs
  (testing "diff payload with --against-map"
    (let [current-src (str "env API_KEY=api_key\n"
                           "file conf/api.txt=api_key\n"
                           "envpath API_KEY_PATH=conf/api.txt\n"
                           "stdin const:new\n")
          against-src (str "env API_KEY=old_key\n"
                           "env OLD=old\n"
                           "file conf/old.txt=old_key\n"
                           "stdin const:old\n")
          {:keys [exit-code stdout stderr]}
          (run-cli ["plan" "--map" "current.kmap" "--against-map" "base.kmap" "--json"]
                   {"current.kmap" current-src
                    "base.kmap" against-src})]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (str/includes? stdout "\"against\":\"base.kmap\""))
      (is (str/includes? stdout "\"diff\":"))
      (is (str/includes? stdout "\"env_changed\""))
      (is (str/includes? stdout "\"files_added\""))
      (is (str/includes? stdout "\"files_removed\""))
      (is (str/includes? stdout "\"envpaths_added\""))
      (is (str/includes? stdout "\"stdin_changed\":true"))))

  (testing "conflicting against inputs are rejected"
    (let [{:keys [exit-code stderr]}
          (run-cli ["plan" "--map" "current.kmap" "--against-map" "base.kmap" "--against-profile" "dev" "--json"]
                   {"current.kmap" "env A=a\n"
                    "base.kmap" "env B=b\n"})]
      (is (= exit-code/code-plan-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"conflicting_against_inputs\""))))

  (testing "invalid against profile names are surfaced"
    (let [{:keys [exit-code stderr]}
          (run-cli ["plan" "--map" "current.kmap" "--against-profile" "../bad" "--json"]
                   {"current.kmap" "env A=a\n"})]
      (is (= exit-code/code-plan-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"invalid_profile_name\"")))))

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

(deftest remote-lifecycle-and-typed-errors
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        id-path (str (.getPath dir) "/bundle.agekey")
        remote-a (str (.getPath dir) "/remote-a")
        remote-b (str (.getPath dir) "/remote-b")]
    (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-path})

    (let [{:keys [exit-code stdout stderr]}
          (run-cli ["remote" "add" "origin" "--path" remote-a "--identity" id-path "--json"] {}
                   {:config-path cfg-path})
          payload (json/read-str stdout)
          remote (get payload "remote")]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (= "remote_add" (get payload "action")))
      (is (= "origin" (get payload "name")))
      (is (= "origin" (get remote "name")))
      (is (= "fs" (get remote "type")))
      (is (= remote-a (get remote "path")))
      (is (str/starts-with? (get remote "recipient") "age1")))

    (let [{:keys [exit-code stdout stderr]}
          (run-cli ["remote" "get" "origin" "--json"] {} {:config-path cfg-path})
          payload (json/read-str stdout)]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (= "remote_get" (get payload "action")))
      (is (= "origin" (get payload "name")))
      (is (= remote-a (get-in payload ["remote" "path"]))))

    (let [{:keys [exit-code stdout stderr]}
          (run-cli ["remote" "set" "origin" "--path" remote-b "--json"] {} {:config-path cfg-path})
          payload (json/read-str stdout)]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (= "remote_set" (get payload "action")))
      (is (= remote-b (get-in payload ["remote" "path"])))
      (is (= false (get payload "baseline_reset"))))

    (let [{:keys [exit-code stdout stderr]}
          (run-cli ["remote" "list" "--json"] {} {:config-path cfg-path})
          payload (json/read-str stdout)]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (= "remote_list" (get payload "action")))
      (is (= 1 (get payload "count")))
      (is (= "origin" (get-in payload ["remotes" 0 "name"]))))

    (let [{:keys [exit-code stdout stderr]}
          (run-cli ["remote" "rm" "origin" "--json"] {} {:config-path cfg-path})
          payload (json/read-str stdout)]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (= "remote_rm" (get payload "action")))
      (is (= "origin" (get payload "name"))))

    (let [{:keys [exit-code stderr]}
          (run-cli ["remote" "add" "bad/name" "--path" remote-a "--json"] {} {:config-path cfg-path})]
      (is (= exit-code/code-remote-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"invalid_remote_name\"")))

    (let [{:keys [exit-code stderr]}
          (run-cli ["remote" "set" "origin" "--json"] {} {:config-path cfg-path})]
      (is (= exit-code/code-remote-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"missing_remote_set_fields\"")))

    (let [{:keys [exit-code stderr]}
          (run-cli ["remote" "add" "origin" "--path" remote-a "--derive-recipient" "--json"] {}
                   {:config-path cfg-path})]
      (is (= exit-code/code-remote-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"missing_identity_for_recipient_derivation\"")))))

(deftest sync-init-create-update-and-errors
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        id-path (str (.getPath dir) "/bundle.agekey")
        remote-a (str (.getPath dir) "/remote-a")
        remote-b (str (.getPath dir) "/remote-b")
        vault-path (str (.getPath dir) "/vault.db")]
    (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-path})
    (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})

    (let [{:keys [exit-code stdout stderr]}
          (run-cli ["sync" "init" "--remote" "origin" "--path" remote-a "--identity" id-path "--json"] {}
                   {:config-path cfg-path})
          payload (json/read-str stdout)]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (= "sync_init" (get payload "action")))
      (is (= "origin" (get payload "remote")))
      (is (= true (get payload "created")))
      (is (= false (get payload "updated")))
      (is (= true (get payload "derived_recipient")))
      (is (= true (get payload "check_ok")))
      (is (= "vault_init" (get payload "recommended_action")))
      (is (= "kimen vault init" (get payload "next_command"))))

    (let [{:keys [exit-code stderr]}
          (run-cli ["sync" "init" "--remote" "origin" "--path" remote-a "--json"] {}
                   {:config-path cfg-path})]
      (is (= exit-code/code-sync-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"remote_exists\"")))

    (let [cfg (json/read-str (slurp cfg-path))]
      (spit cfg-path
            (str (json/write-str (assoc cfg "sync" {"origin" {"last_seen_rev" "abc123"}}))
                 "\n")))

    (let [{:keys [exit-code stdout stderr]}
          (run-cli ["sync" "init" "--remote" "origin" "--update" "--path" remote-b "--json"] {}
                   {:config-path cfg-path})
          payload (json/read-str stdout)]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (= true (get payload "updated")))
      (is (= true (get payload "baseline_reset")))
      (is (= remote-b (get-in payload ["remote_config" "path"]))))

    (let [{:keys [exit-code stderr]}
          (run-cli ["sync" "init" "--remote" "bad/name" "--path" remote-a "--json"] {}
                   {:config-path cfg-path})]
      (is (= exit-code/code-sync-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"invalid_remote_name\"")))))

(deftest sync-status-selection-and-errors
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        remote-a (str (.getPath dir) "/remote-a")
        remote-b (str (.getPath dir) "/remote-b")
        remote-origin (str (.getPath dir) "/remote-origin")]
    (let [{:keys [exit-code stderr]} (run-cli ["sync" "status" "--json"] {} {:config-path cfg-path})]
      (is (= exit-code/code-sync-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"no_remotes_configured\"")))

    (run-cli ["remote" "add" "alpha" "--path" remote-a "--json"] {} {:config-path cfg-path})
    (run-cli ["remote" "add" "beta" "--path" remote-b "--json"] {} {:config-path cfg-path})

    (let [{:keys [exit-code stderr]} (run-cli ["sync" "status" "--json"] {} {:config-path cfg-path})]
      (is (= exit-code/code-sync-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"multiple_remotes_configured\"")))

    (let [{:keys [exit-code stdout stderr]}
          (run-cli ["sync" "status" "--remote" "alpha" "--json"] {} {:config-path cfg-path})
          payload (json/read-str stdout)]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (= "sync_status" (get payload "action")))
      (is (= "alpha" (get payload "remote"))))

    (run-cli ["remote" "add" "origin" "--path" remote-origin "--json"] {} {:config-path cfg-path})
    (let [{:keys [exit-code stdout stderr]}
          (run-cli ["sync" "status" "--json"] {} {:config-path cfg-path})
          payload (json/read-str stdout)]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (= "origin" (get payload "remote"))))))

(deftest sync-status-and-conflicts-strict-and-stale-threshold
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        vault-path (str (.getPath dir) "/vault.db")
        id-path (str (.getPath dir) "/sync.agekey")
        remote-dir (str (.getPath dir) "/remote")
        pass-cmd "printf test-passphrase"]
    (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (run-cli ["secret" "set" "api_key" "--value" "value" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
             {:config-path cfg-path})
    (let [{:keys [stdout]} (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-path})
          keygen (json/read-str stdout)
          recipient (get keygen "recipient")]
      (run-cli ["sync" "init" "--remote" "origin" "--path" remote-dir "--identity" id-path "--recipient" recipient "--json"] {}
               {:config-path cfg-path})
      (let [{:keys [stdout]} (run-cli ["sync" "push" "--remote" "origin" "--passphrase-cmd" pass-cmd "--json"] {}
                                      {:config-path cfg-path})
            push-payload (json/read-str stdout)
            bundle-path (get push-payload "bundle_path")
            lock-path (str bundle-path ".lock")
            _ (spit lock-path "held\n")
            _ (.setLastModified (io/file lock-path) (- (System/currentTimeMillis) (* 2 60 60 1000)))]
        (let [{:keys [exit-code stdout stderr]}
              (run-cli ["sync" "status" "--remote" "origin" "--stale-threshold" "1h" "--json"] {}
                       {:config-path cfg-path})
              payload (json/read-str stdout)]
          (is (= 0 exit-code))
          (is (nil? stderr))
          (is (= true (get payload "has_lock")))
          (is (= true (get payload "likely_stale")))
          (is (>= (get payload "lock_age_seconds") 3600))
          (is (= lock-path (get payload "lock_path"))))

        (let [{:keys [exit-code stderr]}
              (run-cli ["sync" "status" "--remote" "origin" "--strict" "--json"] {}
                       {:config-path cfg-path})]
          (is (= exit-code/code-sync-failed exit-code))
          (is (str/includes? stderr "\"reason\":\"remote_lock_present\"")))

        (let [{:keys [exit-code stderr]}
              (run-cli ["sync" "conflicts" "--remote" "origin" "--strict" "--json"] {}
                       {:config-path cfg-path})]
          (is (= exit-code/code-sync-failed exit-code))
          (is (str/includes? stderr "\"reason\":\"remote_lock_present\"")))

        (let [{:keys [exit-code stderr]}
              (run-cli ["sync" "status" "--remote" "origin" "--stale-threshold" "-1s" "--json"] {}
                       {:config-path cfg-path})]
          (is (= exit-code/code-sync-failed exit-code))
          (is (str/includes? stderr "\"reason\":\"invalid_stale_threshold\"")))))))

(deftest sync-status-and-conflicts-strict-report-remote-changed
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        vault-path (str (.getPath dir) "/vault.db")
        id-path (str (.getPath dir) "/sync.agekey")
        remote-dir (str (.getPath dir) "/remote")
        pass-cmd "printf test-passphrase"]
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (run-cli ["secret" "set" "api_key" "--value" "value" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
             {:config-path cfg-path})
    (let [{:keys [stdout]} (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-path})
          keygen (json/read-str stdout)
          recipient (get keygen "recipient")]
      (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})
      (run-cli ["sync" "init" "--remote" "origin" "--path" remote-dir "--identity" id-path "--recipient" recipient "--json"] {}
               {:config-path cfg-path})
      (let [{:keys [stdout]} (run-cli ["sync" "push" "--remote" "origin" "--passphrase-cmd" pass-cmd "--json"] {}
                                      {:config-path cfg-path})
            push-payload (json/read-str stdout)
            bundle-path (get push-payload "bundle_path")]
        (spit bundle-path "tampered-remote\n")
        (let [{:keys [exit-code stderr]} (run-cli ["sync" "status" "--remote" "origin" "--strict" "--json"] {}
                                                  {:config-path cfg-path})
              err (json/read-str stderr)]
          (is (= exit-code/code-sync-conflict exit-code))
          (is (= "remote_changed" (get err "reason")))
          (is (= "sync_pull" (get err "recommended_action")))
          (is (string? (get err "expected_rev")))
          (is (string? (get err "actual_rev"))))
        (let [{:keys [exit-code stderr]} (run-cli ["sync" "conflicts" "--remote" "origin" "--strict" "--json"] {}
                                                  {:config-path cfg-path})
              err (json/read-str stderr)]
          (is (= exit-code/code-sync-conflict exit-code))
          (is (= "remote_changed" (get err "reason")))
          (is (= "sync_pull" (get err "recommended_action")))
          (is (string? (get err "expected_rev")))
          (is (string? (get err "actual_rev"))))))))

(deftest sync-push-pull-roundtrip
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        vault-path (str (.getPath dir) "/vault.db")
        id-path (str (.getPath dir) "/sync.agekey")
        remote-dir (str (.getPath dir) "/remote")
        pass-cmd "printf test-passphrase"]
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (run-cli ["secret" "set" "api_key" "--value" "shh" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
             {:config-path cfg-path})
    (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-path})
    (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})

    (let [{:keys [exit-code stdout stderr]}
          (run-cli ["sync" "init" "--remote" "origin" "--path" remote-dir "--identity" id-path "--json"] {}
                   {:config-path cfg-path})
          payload (json/read-str stdout)]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (= "sync_init" (get payload "action"))))

    (let [{:keys [exit-code stdout stderr]}
          (run-cli ["sync" "push" "--remote" "origin" "--dry-run" "--json"] {}
                   {:config-path cfg-path})
          payload (json/read-str stdout)]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (= "sync_push_dry_run" (get payload "action")))
      (is (= true (get payload "dry_run"))))

    (let [{:keys [exit-code stdout stderr]}
          (run-cli ["sync" "push" "--remote" "origin" "--json"] {}
                   {:config-path cfg-path})
          payload (json/read-str stdout)]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (= "sync_push" (get payload "action")))
      (is (string? (get payload "remote_rev")))
      (is (not (str/blank? (get payload "remote_rev")))))

    (.delete (io/file vault-path))
    (let [{:keys [exit-code stdout stderr]}
          (run-cli ["sync" "status" "--remote" "origin" "--json"] {}
                   {:config-path cfg-path})
          payload (json/read-str stdout)]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (= false (get payload "has_local")))
      (is (= "sync_pull" (get payload "recommended_action"))))

    (let [{:keys [exit-code stdout stderr]}
          (run-cli ["sync" "pull" "--remote" "origin" "--json"] {}
                   {:config-path cfg-path})
          payload (json/read-str stdout)]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (= "sync_pull" (get payload "action")))
      (is (= true (get payload "has_local"))))

    (let [{:keys [exit-code stdout]} (run-cli ["secret" "get" "api_key" "--unsafe-stdout" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                                              {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"value_b64\":\"c2ho\"")))))

(deftest sync-pull-dry-run-and-reconcile-actions
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        vault-path (str (.getPath dir) "/vault.db")
        id-path (str (.getPath dir) "/sync.agekey")
        remote-dir (str (.getPath dir) "/remote")
        pass-cmd "printf test-passphrase"]
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (run-cli ["secret" "set" "api_key" "--value" "shh" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
             {:config-path cfg-path})
    (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-path})
    (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})
    (run-cli ["sync" "init" "--remote" "origin" "--path" remote-dir "--identity" id-path "--json"] {}
             {:config-path cfg-path})
    (run-cli ["sync" "push" "--remote" "origin" "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})

    (let [{:keys [exit-code stdout stderr]}
          (run-cli ["sync" "pull" "--remote" "origin" "--dry-run" "--json"] {}
                   {:config-path cfg-path})
          payload (json/read-str stdout)]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (= "sync_pull_dry_run" (get payload "action")))
      (is (= true (get payload "would_backup")))
      (is (= true (get payload "has_local"))))

    (let [{:keys [exit-code stdout stderr]}
          (run-cli ["sync" "pull" "--remote" "origin" "--no-backup" "--json"] {}
                   {:config-path cfg-path})
          payload (json/read-str stdout)]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (= "sync_pull" (get payload "action")))
      (is (nil? (get payload "backup_path"))))

    (.delete (io/file vault-path))

    (let [{:keys [exit-code stdout stderr]}
          (run-cli ["sync" "pull" "--remote" "origin" "--dry-run" "--json"] {}
                   {:config-path cfg-path})
          payload (json/read-str stdout)]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (= "sync_pull_dry_run" (get payload "action")))
      (is (= true (get payload "dry_run")))
      (is (= false (get payload "would_backup"))))

    (let [{:keys [exit-code stdout stderr]}
          (run-cli ["sync" "pull" "--remote" "origin" "--reconcile" "--passphrase-cmd" pass-cmd "--json"] {}
                   {:config-path cfg-path})
          payload (json/read-str stdout)]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (= "sync_pull_reconcile" (get payload "action")))
      (is (= true (get payload "reconcile")))
      (is (= 0 (get payload "merged_key_count"))))))

(deftest sync-push-pull-typed-errors
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        vault-path (str (.getPath dir) "/vault.db")
        remote-dir (str (.getPath dir) "/remote")
        pass-cmd "printf test-passphrase"]
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})
    (run-cli ["remote" "add" "origin" "--path" remote-dir "--json"] {} {:config-path cfg-path})

    (let [{:keys [exit-code stderr]}
          (run-cli ["sync" "push" "--remote" "origin" "--json"] {}
                   {:config-path cfg-path})]
      (is (= exit-code/code-sync-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"remote_recipient_missing\"")))

    (let [fresh-cfg (str (.getPath dir) "/fresh-config.json")]
      (run-cli ["remote" "add" "origin" "--path" remote-dir "--recipient" "age1kimen1234567890abcdef1234567890abcdef12" "--json"] {}
               {:config-path fresh-cfg})
      (run-cli ["config" "vault" "set" (str (.getPath dir) "/missing-vault.db") "--json"] {} {:config-path fresh-cfg})
      (let [{:keys [exit-code stderr]}
            (run-cli ["sync" "push" "--remote" "origin" "--json"] {}
                     {:config-path fresh-cfg})]
        (is (= exit-code/code-sync-failed exit-code))
        (is (str/includes? stderr "\"reason\":\"local_vault_missing\""))))

    (let [{:keys [exit-code stderr]}
          (run-cli ["sync" "pull" "--remote" "origin" "--json"] {}
                   {:config-path cfg-path})]
      (is (= exit-code/code-sync-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"remote_identity_missing\"")))))

(deftest sync-push-detects-remote-drift
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        vault-path (str (.getPath dir) "/vault.db")
        id-path (str (.getPath dir) "/sync.agekey")
        remote-dir (str (.getPath dir) "/remote")
        pass-cmd "printf test-passphrase"]
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (run-cli ["secret" "set" "api_key" "--value" "shh" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
             {:config-path cfg-path})
    (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-path})
    (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})
    (run-cli ["sync" "init" "--remote" "origin" "--path" remote-dir "--identity" id-path "--json"] {}
             {:config-path cfg-path})
    (let [{:keys [stdout]} (run-cli ["sync" "push" "--remote" "origin" "--json"] {} {:config-path cfg-path})
          payload (json/read-str stdout)
          bundle-path (get payload "bundle_path")]
      (spit bundle-path "tampered-remote\n")
      (let [{:keys [exit-code stderr]}
            (run-cli ["sync" "push" "--remote" "origin" "--json"] {}
                     {:config-path cfg-path})
            err (json/read-str stderr)]
        (is (= exit-code/code-sync-conflict exit-code))
        (is (= "remote_changed" (get err "reason")))
        (is (= "sync_pull" (get err "recommended_action")))
        (is (string? (get err "expected_rev")))
        (is (string? (get err "actual_rev")))))))

(deftest sync-push-conflict-when-remote-disappeared
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        vault-path (str (.getPath dir) "/vault.db")
        id-path (str (.getPath dir) "/sync.agekey")
        remote-dir (str (.getPath dir) "/remote")
        pass-cmd "printf test-passphrase"]
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (run-cli ["secret" "set" "api_key" "--value" "shh" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
             {:config-path cfg-path})
    (let [{:keys [stdout]} (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-path})
          keygen (json/read-str stdout)
          recipient (get keygen "recipient")]
      (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})
      (run-cli ["sync" "init" "--remote" "origin" "--path" remote-dir "--identity" id-path "--recipient" recipient "--json"] {}
               {:config-path cfg-path})
      (let [{:keys [stdout]} (run-cli ["sync" "push" "--remote" "origin" "--passphrase-cmd" pass-cmd "--json"] {}
                                      {:config-path cfg-path})
            payload (json/read-str stdout)
            bundle-path (get payload "bundle_path")]
        (.delete (io/file bundle-path))
        (let [{:keys [exit-code stderr]}
              (run-cli ["sync" "push" "--remote" "origin" "--json"] {}
                       {:config-path cfg-path})
              err (json/read-str stderr)]
          (is (= exit-code/code-sync-conflict exit-code))
          (is (= "remote_disappeared" (get err "reason")))
          (is (= "sync_reset_baseline_or_remote_recreate" (get err "recommended_action")))
          (is (string? (get err "expected_rev"))))))))

(deftest sync-status-and-conflicts-report-remote-disappeared
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        vault-path (str (.getPath dir) "/vault.db")
        id-path (str (.getPath dir) "/sync.agekey")
        remote-dir (str (.getPath dir) "/remote")
        pass-cmd "printf test-passphrase"]
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (run-cli ["secret" "set" "api_key" "--value" "shh" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
             {:config-path cfg-path})
    (let [{:keys [stdout]} (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-path})
          keygen (json/read-str stdout)
          recipient (get keygen "recipient")]
      (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})
      (run-cli ["sync" "init" "--remote" "origin" "--path" remote-dir "--identity" id-path "--recipient" recipient "--json"] {}
               {:config-path cfg-path})
      (let [{:keys [stdout]} (run-cli ["sync" "push" "--remote" "origin" "--passphrase-cmd" pass-cmd "--json"] {}
                                      {:config-path cfg-path})
            payload (json/read-str stdout)
            bundle-path (get payload "bundle_path")]
        (.delete (io/file bundle-path))
        (let [{:keys [exit-code stdout stderr]}
              (run-cli ["sync" "status" "--remote" "origin" "--json"] {}
                       {:config-path cfg-path})
              status (json/read-str stdout)]
          (is (= 0 exit-code))
          (is (nil? stderr))
          (is (= true (get status "has_conflict")))
          (is (= "remote_disappeared" (get status "reason")))
          (is (= "remote_disappeared" (first (get status "blockers"))))
          (is (= "sync_reset_baseline_or_remote_recreate" (get status "recommended_action"))))
        (let [{:keys [exit-code stderr]}
              (run-cli ["sync" "status" "--remote" "origin" "--strict" "--json"] {}
                       {:config-path cfg-path})
              err (json/read-str stderr)]
          (is (= exit-code/code-sync-conflict exit-code))
          (is (= "remote_disappeared" (get err "reason")))
          (is (= "sync_reset_baseline_or_remote_recreate" (get err "recommended_action"))))
        (let [{:keys [exit-code stdout stderr]}
              (run-cli ["sync" "conflicts" "--remote" "origin" "--json"] {}
                       {:config-path cfg-path})
              conflicts (json/read-str stdout)]
          (is (= 0 exit-code))
          (is (nil? stderr))
          (is (= true (get conflicts "has_conflict")))
          (is (= "remote_disappeared" (get conflicts "reason")))
          (is (= "remote_disappeared" (first (get conflicts "blockers"))))
          (is (= "sync_reset_baseline_or_remote_recreate" (get conflicts "recommended_action"))))
        (let [{:keys [exit-code stderr]}
              (run-cli ["sync" "conflicts" "--remote" "origin" "--strict" "--json"] {}
                       {:config-path cfg-path})
              err (json/read-str stderr)]
          (is (= exit-code/code-sync-conflict exit-code))
          (is (= "remote_disappeared" (get err "reason")))
          (is (= "sync_reset_baseline_or_remote_recreate" (get err "recommended_action"))))))))

(deftest sync-conflicts-reports-local-and-remote-drift
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        vault-path (str (.getPath dir) "/vault.db")
        id-path (str (.getPath dir) "/sync.agekey")
        remote-dir (str (.getPath dir) "/remote")
        pass-cmd "printf test-passphrase"]
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (run-cli ["secret" "set" "api_key" "--value" "shh" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
             {:config-path cfg-path})
    (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-path})
    (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})
    (run-cli ["sync" "init" "--remote" "origin" "--path" remote-dir "--identity" id-path "--json"] {}
             {:config-path cfg-path})
    (let [{:keys [stdout]} (run-cli ["sync" "push" "--remote" "origin" "--json"] {} {:config-path cfg-path})
          payload (json/read-str stdout)
          bundle-path (get payload "bundle_path")]
      (run-cli ["secret" "set" "api_key" "--value" "new-local" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
               {:config-path cfg-path})
      (spit bundle-path "tampered-remote\n")
      (let [{:keys [exit-code stdout stderr]}
            (run-cli ["sync" "conflicts" "--remote" "origin" "--json"] {}
                     {:config-path cfg-path})
            conflicts (json/read-str stdout)]
        (is (= 0 exit-code))
        (is (nil? stderr))
        (is (= "sync_conflicts" (get conflicts "action")))
        (is (= true (get conflicts "remote_changed")))
        (is (= true (get conflicts "local_changed")))
        (is (= true (get conflicts "has_conflict")))
        (is (= "remote_changed" (get conflicts "reason")))
        (is (= "remote_changed" (first (get conflicts "blockers"))))
        (is (= "sync_pull" (get conflicts "recommended_action")))))))

(deftest sync-push-force-overrides-drift-and-baseline-guards
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        fresh-cfg (str (.getPath dir) "/fresh-config.json")
        vault-path (str (.getPath dir) "/vault.db")
        id-path (str (.getPath dir) "/sync.agekey")
        remote-dir (str (.getPath dir) "/remote")
        pass-cmd "printf test-passphrase"]
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (run-cli ["secret" "set" "api_key" "--value" "shh" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
             {:config-path cfg-path})
    (let [{:keys [stdout]} (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-path})
          keygen (json/read-str stdout)
          recipient (get keygen "recipient")]
      (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})
      (run-cli ["sync" "init" "--remote" "origin" "--path" remote-dir "--identity" id-path "--recipient" recipient "--json"] {}
               {:config-path cfg-path})
      (let [{:keys [stdout]} (run-cli ["sync" "push" "--remote" "origin" "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
            payload (json/read-str stdout)
            bundle-path (get payload "bundle_path")]
        (spit bundle-path "tampered-remote\n")

        (let [{:keys [exit-code stderr]}
              (run-cli ["sync" "push" "--remote" "origin" "--json"] {}
                       {:config-path cfg-path})
              err (json/read-str stderr)]
          (is (= exit-code/code-sync-conflict exit-code))
          (is (= "remote_changed" (get err "reason")))
          (is (= "sync_pull" (get err "recommended_action")))
          (is (string? (get err "expected_rev")))
          (is (string? (get err "actual_rev"))))

        (let [{:keys [exit-code stdout stderr]}
              (run-cli ["sync" "push" "--remote" "origin" "--force" "--json"] {}
                       {:config-path cfg-path})
              force-payload (json/read-str stdout)]
          (is (= 0 exit-code))
          (is (nil? stderr))
          (is (= "sync_push" (get force-payload "action")))
          (is (= true (get force-payload "forced"))))

        (run-cli ["remote" "add" "origin" "--path" remote-dir "--recipient" recipient "--json"] {}
                 {:config-path fresh-cfg})
        (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path fresh-cfg})

        (let [{:keys [exit-code stderr]}
              (run-cli ["sync" "push" "--remote" "origin" "--json"] {}
                       {:config-path fresh-cfg})
              err (json/read-str stderr)]
          (is (= exit-code/code-sync-conflict exit-code))
          (is (= "no_local_baseline" (get err "reason")))
          (is (= "sync_pull" (get err "recommended_action")))
          (is (string? (get err "actual_rev"))))

        (let [{:keys [exit-code stdout stderr]}
              (run-cli ["sync" "push" "--remote" "origin" "--force" "--json"] {}
                       {:config-path fresh-cfg})
              force-payload (json/read-str stdout)]
          (is (= 0 exit-code))
          (is (nil? stderr))
          (is (= "sync_push" (get force-payload "action")))
          (is (= true (get force-payload "forced"))))))))

(deftest sync-push-lock-wait-and-break-stale-lock
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        vault-path (str (.getPath dir) "/vault.db")
        id-path (str (.getPath dir) "/sync.agekey")
        remote-dir (str (.getPath dir) "/remote")
        pass-cmd "printf test-passphrase"]
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (run-cli ["secret" "set" "api_key" "--value" "shh" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
             {:config-path cfg-path})
    (let [{:keys [stdout]} (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-path})
          keygen (json/read-str stdout)
          recipient (get keygen "recipient")]
      (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})
      (run-cli ["sync" "init" "--remote" "origin" "--path" remote-dir "--identity" id-path "--recipient" recipient "--json"] {}
               {:config-path cfg-path})
      (let [{:keys [stdout]} (run-cli ["sync" "push" "--remote" "origin" "--passphrase-cmd" pass-cmd "--json"] {}
                                      {:config-path cfg-path})
            push-payload (json/read-str stdout)
            bundle-path (get push-payload "bundle_path")
            lock-path (str bundle-path ".lock")
            lock-file (io/file lock-path)]
        (spit lock-path "held\n")

        (let [{:keys [exit-code stderr]} (run-cli ["sync" "push" "--remote" "origin" "--lock-wait" "-1s" "--json"] {}
                                                  {:config-path cfg-path})]
          (is (= exit-code/code-sync-failed exit-code))
          (is (str/includes? stderr "\"reason\":\"invalid_lock_wait\"")))

        (let [{:keys [exit-code stderr]} (run-cli ["sync" "push" "--remote" "origin" "--break-stale-lock-after" "-1s" "--json"] {}
                                                  {:config-path cfg-path})]
          (is (= exit-code/code-sync-failed exit-code))
          (is (str/includes? stderr "\"reason\":\"invalid_break_stale_lock_after\"")))

        (let [{:keys [exit-code stderr]} (run-cli ["sync" "push" "--remote" "origin" "--dry-run" "--lock-wait" "1s" "--json"] {}
                                                  {:config-path cfg-path})]
          (is (= exit-code/code-sync-failed exit-code))
          (is (str/includes? stderr "\"reason\":\"conflicting_dry_run_lock_flags\"")))

        (let [{:keys [exit-code stderr]} (run-cli ["sync" "push" "--remote" "origin" "--lock-wait" "20ms" "--json"] {}
                                                  {:config-path cfg-path})]
          (is (= exit-code/code-sync-failed exit-code))
          (is (str/includes? stderr "\"reason\":\"remote_lock_present\"")))

        (.setLastModified lock-file (- (System/currentTimeMillis) (* 2 60 1000)))
        (let [{:keys [exit-code stdout stderr]} (run-cli ["sync" "push" "--remote" "origin" "--break-stale-lock-after" "1m" "--passphrase-cmd" pass-cmd "--json"] {}
                                                         {:config-path cfg-path})
              payload (json/read-str stdout)]
          (is (= 0 exit-code))
          (is (nil? stderr))
          (is (= true (get payload "stale_lock_broken")))
          (is (false? (.exists lock-file))))))))

(deftest sync-pull-reconcile-required-for-overlapping-changes
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        vault-path (str (.getPath dir) "/vault.db")
        remote-vault-path (str (.getPath dir) "/remote-vault.db")
        id-path (str (.getPath dir) "/sync.agekey")
        remote-dir (str (.getPath dir) "/remote")
        pass-cmd "printf test-passphrase"]
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (run-cli ["secret" "set" "api_key" "--value" "shh" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
             {:config-path cfg-path})
    (let [{:keys [stdout]} (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-path})
          keygen (json/read-str stdout)
          recipient (get keygen "recipient")]
      (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})
      (run-cli ["sync" "init" "--remote" "origin" "--path" remote-dir "--identity" id-path "--recipient" recipient "--json"] {}
               {:config-path cfg-path})
      (let [{:keys [stdout]} (run-cli ["sync" "push" "--remote" "origin" "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
            payload (json/read-str stdout)
            bundle-path (get payload "bundle_path")]
        (run-cli ["secret" "set" "api_key" "--value" "local-change" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                 {:config-path cfg-path})
        (run-cli ["vault" "init" "--vault" remote-vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                 {:config-path cfg-path})
        (run-cli ["secret" "set" "api_key" "--value" "remote-win" "--vault" remote-vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                 {:config-path cfg-path})
        (run-cli ["bundle" "seal" "--vault" remote-vault-path "--out" bundle-path "--recipient" recipient "--json"] {}
                 {:config-path cfg-path})

        (let [{:keys [exit-code stderr]}
              (run-cli ["sync" "pull" "--remote" "origin" "--json"] {}
                       {:config-path cfg-path})]
          (is (= exit-code/code-sync-conflict exit-code))
          (is (str/includes? stderr "\"reason\":\"overlapping_changes\"")))

        (let [{:keys [exit-code stderr]}
              (run-cli ["sync" "pull" "--remote" "origin" "--dry-run" "--json"] {}
                       {:config-path cfg-path})]
          (is (= exit-code/code-sync-conflict exit-code))
          (is (str/includes? stderr "\"reason\":\"overlapping_changes\"")))

        (let [{:keys [exit-code stderr]}
              (run-cli ["sync" "pull" "--remote" "origin" "--reconcile" "--passphrase-cmd" pass-cmd "--json"] {}
                       {:config-path cfg-path})]
          (is (= exit-code/code-sync-conflict exit-code))
          (is (str/includes? stderr "\"reason\":\"overlapping_changes\"")))))))

(deftest sync-auto-rejects-conflicting-check-and-dry-run
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        vault-path (str (.getPath dir) "/vault.db")
        pass-cmd "printf test-passphrase"]
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})
    (let [{:keys [exit-code stderr]}
          (run-cli ["sync" "--check" "--dry-run" "--json"] {}
                   {:config-path cfg-path})]
      (is (= exit-code/code-sync-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"conflicting_check_and_dry_run\"")))))

(deftest sync-auto-noop-and-push-flow
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        vault-path (str (.getPath dir) "/vault.db")
        id-path (str (.getPath dir) "/sync.agekey")
        remote-dir (str (.getPath dir) "/remote")
        pass-cmd "printf test-passphrase"]
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (run-cli ["secret" "set" "api_key" "--value" "shh" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
             {:config-path cfg-path})
    (let [{:keys [stdout]} (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-path})
          keygen (json/read-str stdout)
          recipient (get keygen "recipient")]
      (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})
      (run-cli ["sync" "init" "--remote" "origin" "--path" remote-dir "--identity" id-path "--recipient" recipient "--json"] {}
               {:config-path cfg-path})
      (run-cli ["sync" "push" "--remote" "origin" "--json"] {} {:config-path cfg-path})

      (let [{:keys [exit-code stdout stderr]}
            (run-cli ["sync" "--remote" "origin" "--json"] {}
                     {:config-path cfg-path})
            payload (json/read-str stdout)]
        (is (= 0 exit-code))
        (is (nil? stderr))
        (is (= "sync" (get payload "action")))
        (is (= "noop" (get payload "decision")))
        (is (= "apply" (get payload "mode")))
        (is (true? (get payload "ok"))))

      (run-cli ["secret" "set" "api_key" "--value" "local-change" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
               {:config-path cfg-path})
      (let [{:keys [exit-code stdout stderr]}
            (run-cli ["sync" "--remote" "origin" "--json"] {}
                     {:config-path cfg-path})
            payload (json/read-str stdout)]
        (is (= 0 exit-code))
        (is (nil? stderr))
        (is (= "sync" (get payload "action")))
        (is (= "push" (get payload "decision")))
        (is (some #(= "sync_push" (get % "name")) (get payload "steps")))))))

(deftest sync-auto-conflict-requires-reconcile
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        vault-path (str (.getPath dir) "/vault.db")
        remote-vault-path (str (.getPath dir) "/remote-vault.db")
        id-path (str (.getPath dir) "/sync.agekey")
        remote-dir (str (.getPath dir) "/remote")
        pass-cmd "printf test-passphrase"]
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (run-cli ["secret" "set" "api_key" "--value" "shh" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
             {:config-path cfg-path})
    (let [{:keys [stdout]} (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-path})
          keygen (json/read-str stdout)
          recipient (get keygen "recipient")]
      (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})
      (run-cli ["sync" "init" "--remote" "origin" "--path" remote-dir "--identity" id-path "--recipient" recipient "--json"] {}
               {:config-path cfg-path})
      (let [{:keys [stdout]} (run-cli ["sync" "push" "--remote" "origin" "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
            payload (json/read-str stdout)
            bundle-path (get payload "bundle_path")]
        (run-cli ["secret" "set" "api_key" "--value" "local-change" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                 {:config-path cfg-path})
        (run-cli ["vault" "init" "--vault" remote-vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                 {:config-path cfg-path})
        (run-cli ["secret" "set" "api_key" "--value" "remote-win" "--vault" remote-vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                 {:config-path cfg-path})
        (run-cli ["bundle" "seal" "--vault" remote-vault-path "--out" bundle-path "--recipient" recipient "--json"] {}
                 {:config-path cfg-path})

        (let [{:keys [exit-code stdout stderr]}
              (run-cli ["sync" "--remote" "origin" "--passphrase-cmd" pass-cmd "--json"] {}
                       {:config-path cfg-path})]
          (is (= exit-code/code-sync-conflict exit-code))
          (is (nil? stderr))
          (let [payload (json/read-str stdout)]
            (is (= "sync" (get payload "action")))
            (is (= "blocked" (get payload "decision")))
            (is (= "overlapping_changes" (get payload "reason")))
            (is (= "manual_reconcile" (get payload "recommended_action")))))

        (let [{:keys [exit-code stdout stderr]}
              (run-cli ["sync" "--remote" "origin" "--reconcile" "--passphrase-cmd" pass-cmd "--json"] {}
                       {:config-path cfg-path})]
          (is (= exit-code/code-sync-conflict exit-code))
          (is (nil? stderr))
          (let [payload (json/read-str stdout)]
            (is (= "sync" (get payload "action")))
            (is (= "blocked" (get payload "decision")))
            (is (= "overlapping_changes" (get payload "reason")))
            (is (= "manual_reconcile" (get payload "recommended_action")))))))))

(deftest sync-auto-pulls-when-remote-changed-local-clean
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-a (str (.getPath dir) "/config-a.json")
        cfg-b (str (.getPath dir) "/config-b.json")
        vault-a (str (.getPath dir) "/vault-a.db")
        vault-b (str (.getPath dir) "/vault-b.db")
        id-path (str (.getPath dir) "/sync.agekey")
        remote-dir (str (.getPath dir) "/remote")
        pass-cmd "printf test-passphrase"]
    (run-cli ["vault" "init" "--vault" vault-a "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-a})
    (run-cli ["secret" "set" "api_key" "--value" "a1" "--vault" vault-a "--passphrase-cmd" pass-cmd "--json"] {}
             {:config-path cfg-a})
    (let [{:keys [stdout]} (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-a})
          keygen (json/read-str stdout)
          recipient (get keygen "recipient")]
      (run-cli ["config" "vault" "set" vault-a "--json"] {} {:config-path cfg-a})
      (run-cli ["sync" "init" "--remote" "origin" "--path" remote-dir "--identity" id-path "--recipient" recipient "--json"] {}
               {:config-path cfg-a})
      (run-cli ["sync" "push" "--remote" "origin" "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-a})

      (run-cli ["vault" "init" "--vault" vault-b "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-b})
      (run-cli ["config" "vault" "set" vault-b "--json"] {} {:config-path cfg-b})
      (run-cli ["sync" "init" "--remote" "origin" "--path" remote-dir "--identity" id-path "--recipient" recipient "--json"] {}
               {:config-path cfg-b})
      (run-cli ["sync" "pull" "--remote" "origin" "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-b})
      (run-cli ["secret" "set" "api_key" "--value" "remote-v2" "--vault" vault-b "--passphrase-cmd" pass-cmd "--json"] {}
               {:config-path cfg-b})
      (run-cli ["sync" "push" "--remote" "origin" "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-b})

      (let [{:keys [exit-code stdout stderr]}
            (run-cli ["sync" "--remote" "origin" "--json"] {}
                     {:config-path cfg-a})
            payload (json/read-str stdout)]
        (is (= 0 exit-code))
        (is (nil? stderr))
        (is (= "sync" (get payload "action")))
        (is (= "pull" (get payload "decision")))
        (is (some #(= "sync_pull" (get % "name")) (get payload "steps"))))

      (let [{:keys [exit-code stdout]} (run-cli ["secret" "get" "api_key" "--unsafe-stdout" "--vault" vault-a "--passphrase-cmd" pass-cmd "--json"] {}
                                                {:config-path cfg-a})
            payload (json/read-str stdout)]
        (is (= 0 exit-code))
        (is (= "cmVtb3RlLXYy" (get payload "value_b64")))))))

(deftest sync-auto-reconciles-disjoint-remote-and-local-changes
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-a (str (.getPath dir) "/config-a.json")
        cfg-b (str (.getPath dir) "/config-b.json")
        vault-a (str (.getPath dir) "/vault-a.db")
        vault-b (str (.getPath dir) "/vault-b.db")
        id-path (str (.getPath dir) "/sync.agekey")
        remote-dir (str (.getPath dir) "/remote")
        pass-cmd "printf test-passphrase"]
    (run-cli ["vault" "init" "--vault" vault-a "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-a})
    (run-cli ["secret" "set" "api_key" "--value" "a1" "--vault" vault-a "--passphrase-cmd" pass-cmd "--json"] {}
             {:config-path cfg-a})
    (run-cli ["secret" "set" "db_pw" "--value" "p1" "--vault" vault-a "--passphrase-cmd" pass-cmd "--json"] {}
             {:config-path cfg-a})
    (let [{:keys [stdout]} (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-a})
          keygen (json/read-str stdout)
          recipient (get keygen "recipient")]
      (run-cli ["config" "vault" "set" vault-a "--json"] {} {:config-path cfg-a})
      (run-cli ["sync" "init" "--remote" "origin" "--path" remote-dir "--identity" id-path "--recipient" recipient "--json"] {}
               {:config-path cfg-a})
      (run-cli ["sync" "push" "--remote" "origin" "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-a})
      (run-cli ["secret" "set" "api_key" "--value" "a2-local" "--vault" vault-a "--passphrase-cmd" pass-cmd "--json"] {}
               {:config-path cfg-a})

      (run-cli ["vault" "init" "--vault" vault-b "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-b})
      (run-cli ["config" "vault" "set" vault-b "--json"] {} {:config-path cfg-b})
      (run-cli ["sync" "init" "--remote" "origin" "--path" remote-dir "--identity" id-path "--recipient" recipient "--json"] {}
               {:config-path cfg-b})
      (run-cli ["sync" "pull" "--remote" "origin" "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-b})
      (run-cli ["secret" "set" "db_pw" "--value" "p2-remote" "--vault" vault-b "--passphrase-cmd" pass-cmd "--json"] {}
               {:config-path cfg-b})
      (run-cli ["sync" "push" "--remote" "origin" "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-b})

      (let [{:keys [exit-code stdout stderr]}
            (run-cli ["sync" "--remote" "origin" "--check" "--json"] {}
                     {:config-path cfg-a})
            payload (json/read-str stdout)]
        (is (= 0 exit-code))
        (is (nil? stderr))
        (is (= "sync" (get payload "action")))
        (is (= "would_pull_reconcile" (get payload "decision"))))

      (let [{:keys [exit-code stdout stderr]}
            (run-cli ["sync" "--remote" "origin" "--passphrase-cmd" pass-cmd "--json"] {}
                     {:config-path cfg-a})
            payload (json/read-str stdout)]
        (is (= 0 exit-code))
        (is (nil? stderr))
        (is (= "sync" (get payload "action")))
        (is (= "pull_reconcile" (get payload "decision")))
        (is (some #(= "sync_pull_reconcile" (get % "name")) (get payload "steps"))))

      (let [{api-stdout :stdout} (run-cli ["secret" "get" "api_key" "--unsafe-stdout" "--vault" vault-a "--passphrase-cmd" pass-cmd "--json"] {}
                                          {:config-path cfg-a})
            api (json/read-str api-stdout)
            {db-stdout :stdout} (run-cli ["secret" "get" "db_pw" "--unsafe-stdout" "--vault" vault-a "--passphrase-cmd" pass-cmd "--json"] {}
                                         {:config-path cfg-a})
            db (json/read-str db-stdout)]
        (is (= "YTItbG9jYWw=" (get api "value_b64")))
        (is (= "cDItcmVtb3Rl" (get db "value_b64")))))))

(deftest sync-transfer-rejects-command-specific-flags
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")]
    (let [{:keys [exit-code stderr]}
          (run-cli ["sync" "push" "--reconcile" "--json"] {}
                   {:config-path cfg-path})]
      (is (= exit-code/code-sync-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"sync_failed\"")))
    (let [{:keys [exit-code stderr]}
          (run-cli ["sync" "push" "--no-backup" "--json"] {}
                   {:config-path cfg-path})]
      (is (= exit-code/code-sync-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"sync_failed\"")))
    (let [{:keys [exit-code stderr]}
          (run-cli ["sync" "pull" "--force" "--json"] {}
                   {:config-path cfg-path})]
      (is (= exit-code/code-sync-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"sync_failed\"")))
    (let [{:keys [exit-code stderr]}
          (run-cli ["sync" "pull" "--lock-wait" "1s" "--json"] {}
                   {:config-path cfg-path})]
      (is (= exit-code/code-sync-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"sync_failed\"")))))

(deftest sync-reset-baseline-clear-and-to-remote
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        vault-path (str (.getPath dir) "/vault.db")
        id-path (str (.getPath dir) "/sync.agekey")
        remote-dir (str (.getPath dir) "/remote")
        pass-cmd "printf test-passphrase"]
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (run-cli ["secret" "set" "api_key" "--value" "shh" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
             {:config-path cfg-path})
    (let [{:keys [stdout]} (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-path})
          keygen (json/read-str stdout)
          recipient (get keygen "recipient")]
      (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})
      (run-cli ["sync" "init" "--remote" "origin" "--path" remote-dir "--identity" id-path "--recipient" recipient "--json"] {}
               {:config-path cfg-path})
      (let [{:keys [stdout]} (run-cli ["sync" "push" "--remote" "origin" "--json"] {} {:config-path cfg-path})
            payload (json/read-str stdout)
            bundle-path (get payload "bundle_path")]
        (spit bundle-path "tampered-remote\n")
        (let [{:keys [exit-code stderr]}
              (run-cli ["sync" "push" "--remote" "origin" "--json"] {}
                       {:config-path cfg-path})]
          (is (= exit-code/code-sync-conflict exit-code))
          (is (str/includes? stderr "\"reason\":\"remote_changed\"")))

        (let [{:keys [exit-code stdout stderr]}
              (run-cli ["sync" "reset-baseline" "--remote" "origin" "--to-remote" "--yes" "--json"] {}
                       {:config-path cfg-path})
              reset-payload (json/read-str stdout)]
          (is (= 0 exit-code))
          (is (nil? stderr))
          (is (= "sync_reset_baseline" (get reset-payload "action")))
          (is (= "to_remote" (get reset-payload "mode")))

          (let [remote-rev (get reset-payload "new_rev")
                {:keys [exit-code stdout stderr]}
                (run-cli ["sync" "reset-baseline" "--remote" "origin" "--rev" remote-rev "--yes" "--json"] {}
                         {:config-path cfg-path})
                reset-payload (json/read-str stdout)]
            (is (= 0 exit-code))
            (is (nil? stderr))
            (is (= "sync_reset_baseline" (get reset-payload "action")))
            (is (= "rev" (get reset-payload "mode")))
            (is (= remote-rev (get reset-payload "new_rev")))))

        (let [{:keys [exit-code stdout stderr]}
              (run-cli ["sync" "push" "--remote" "origin" "--json"] {}
                       {:config-path cfg-path})
              push-payload (json/read-str stdout)]
          (is (= 0 exit-code))
          (is (nil? stderr))
          (is (= "sync_push" (get push-payload "action"))))

        (let [{:keys [exit-code stderr]}
              (run-cli ["sync" "reset-baseline" "--remote" "origin" "--clear" "--json"] {}
                       {:config-path cfg-path})]
          (is (= exit-code/code-sync-failed exit-code))
          (is (str/includes? stderr "\"reason\":\"sync_failed\"")))

        (let [{:keys [exit-code stdout stderr]}
              (run-cli ["sync" "reset-baseline" "--remote" "origin" "--clear" "--yes" "--json"] {}
                       {:config-path cfg-path})
              reset-payload (json/read-str stdout)]
          (is (= 0 exit-code))
          (is (nil? stderr))
          (is (= "sync_reset_baseline" (get reset-payload "action")))
          (is (= "clear" (get reset-payload "mode"))))

        (let [{:keys [exit-code stderr]}
              (run-cli ["sync" "push" "--remote" "origin" "--json"] {}
                       {:config-path cfg-path})]
          (is (= exit-code/code-sync-conflict exit-code))
          (is (str/includes? stderr "\"reason\":\"no_local_baseline\"")))))))

(deftest sync-restore-restores-vault-from-backup
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        vault-path (str (.getPath dir) "/vault.db")
        backup-path (str (.getPath dir) "/vault.backup.db")
        pass-cmd "printf test-passphrase"]
    (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (run-cli ["secret" "set" "api_key" "--value" "restore-source" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
             {:config-path cfg-path})
    (spit backup-path (slurp vault-path))
    (run-cli ["secret" "set" "api_key" "--value" "restore-mutated" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
             {:config-path cfg-path})

    (let [{:keys [exit-code stdout stderr]}
          (run-cli ["sync" "restore" "--backup" backup-path "--json"] {}
                   {:config-path cfg-path})
          payload (json/read-str stdout)]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (= "sync_restore" (get payload "action")))
      (is (= true (get payload "restored")))
      (is (= backup-path (get payload "source_backup_path")))
      (is (string? (get payload "current_backup_path")))
      (is (.exists (io/file (get payload "current_backup_path")))))

    (let [{:keys [exit-code stdout]}
          (run-cli ["secret" "get" "api_key" "--unsafe-stdout" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                   {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"value_b64\":\"cmVzdG9yZS1zb3VyY2U=\"")))

    (run-cli ["secret" "set" "api_key" "--value" "restore-mutated-2" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
             {:config-path cfg-path})
    (let [{:keys [exit-code stdout stderr]}
          (run-cli ["sync" "restore" "--backup" backup-path "--no-backup" "--json"] {}
                   {:config-path cfg-path})
          payload (json/read-str stdout)]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (= true (get payload "restored")))
      (is (nil? (get payload "current_backup_path"))))

    (let [{:keys [exit-code stderr]}
          (run-cli ["sync" "restore" "--backup" (str (.getPath dir) "/missing.backup") "--json"] {}
                   {:config-path cfg-path})]
      (is (= exit-code/code-sync-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"input_missing\"")))))

(deftest sync-unlock-removes-lock-with-guards
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        vault-path (str (.getPath dir) "/vault.db")
        id-path (str (.getPath dir) "/sync.agekey")
        remote-dir (str (.getPath dir) "/remote")
        lock-path (str remote-dir "/vault.age.lock")
        pass-cmd "printf test-passphrase"]
    (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (run-cli ["secret" "set" "api_key" "--value" "value" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
             {:config-path cfg-path})
    (let [{:keys [stdout]} (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-path})
          keygen (json/read-str stdout)
          recipient (get keygen "recipient")]
      (run-cli ["sync" "init" "--remote" "origin" "--path" remote-dir "--identity" id-path "--recipient" recipient "--json"] {}
               {:config-path cfg-path})
      (.mkdirs (io/file remote-dir))
      (spit lock-path "locked\n")

      (let [{:keys [exit-code stderr]} (run-cli ["sync" "unlock" "--remote" "origin" "--json"] {}
                                                {:config-path cfg-path})]
        (is (= exit-code/code-sync-failed exit-code))
        (is (str/includes? stderr "\"reason\":\"unlock_confirmation_required\"")))

      (let [{:keys [exit-code stderr]} (run-cli ["sync" "unlock" "--remote" "origin" "--if-older-than" "5m" "--yes" "--json"] {}
                                                {:config-path cfg-path})]
        (is (= exit-code/code-sync-failed exit-code))
        (is (str/includes? stderr "\"reason\":\"lock_too_new\"")))

      (let [{:keys [exit-code stderr]} (run-cli ["sync" "unlock" "--remote" "origin" "--if-older-than" "-1s" "--yes" "--json"] {}
                                                {:config-path cfg-path})]
        (is (= exit-code/code-sync-failed exit-code))
        (is (str/includes? stderr "\"reason\":\"invalid_if_older_than\"")))

      (let [{:keys [exit-code stdout stderr]} (run-cli ["sync" "unlock" "--remote" "origin" "--yes" "--json"] {}
                                                       {:config-path cfg-path})
            payload (json/read-str stdout)]
        (is (= 0 exit-code))
        (is (nil? stderr))
        (is (= "sync_unlock" (get payload "action")))
        (is (= true (get payload "removed"))))

      (let [{:keys [exit-code stdout stderr]} (run-cli ["sync" "unlock" "--remote" "origin" "--yes" "--json"] {}
                                                       {:config-path cfg-path})
            payload (json/read-str stdout)]
        (is (= 0 exit-code))
        (is (nil? stderr))
        (is (= false (get payload "removed")))
        (is (= "lock_missing" (get payload "reason")))))))

(deftest sync-preflight-runs-checks-and-returns-json-on-failure
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        vault-path (str (.getPath dir) "/vault.db")
        id-path (str (.getPath dir) "/sync.agekey")
        remote-dir (str (.getPath dir) "/remote")
        pass-cmd "printf test-passphrase"]
    (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (run-cli ["secret" "set" "api_key" "--value" "value" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
             {:config-path cfg-path})
    (let [{:keys [stdout]} (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-path})
          keygen (json/read-str stdout)
          recipient (get keygen "recipient")]
      (run-cli ["sync" "init" "--remote" "origin" "--path" remote-dir "--identity" id-path "--recipient" recipient "--json"] {}
               {:config-path cfg-path})
      (run-cli ["sync" "push" "--remote" "origin" "--json"] {} {:config-path cfg-path})

      (let [{:keys [exit-code stdout stderr]} (run-cli ["sync" "preflight" "--remote" "origin" "--json"] {}
                                                       {:config-path cfg-path})
            payload (json/read-str stdout)
            checks (get payload "checks")]
        (is (= 0 exit-code))
        (is (nil? stderr))
        (is (= "sync_preflight" (get payload "action")))
        (is (= true (get payload "ok")))
        (is (= 5 (get payload "check_count")))
        (is (= 0 (get payload "failed_count")))
        (is (= ["doctor" "sync_status" "sync_conflicts" "sync_pull_dry_run" "sync_push_dry_run"]
               (mapv #(get % "name") checks))))

      (let [{:keys [exit-code stdout]} (run-cli ["sync" "preflight" "--remote" "origin" "--json" "--only" "status" "--only" "conflicts" "--skip" "conflicts"] {}
                                                {:config-path cfg-path})
            payload (json/read-str stdout)]
        (is (= 0 exit-code))
        (is (= 1 (get payload "check_count")))
        (is (= ["sync_status"] (mapv #(get % "name") (get payload "checks")))))

      (let [lock-path (str remote-dir "/vault.age.lock")]
        (spit lock-path "locked\n")
        (let [{:keys [exit-code stdout stderr]} (run-cli ["sync" "preflight" "--remote" "origin" "--strict" "--json"] {}
                                                         {:config-path cfg-path})
              payload (json/read-str stdout)]
          (is (= exit-code/code-sync-failed exit-code))
          (is (nil? stderr))
          (is (= "sync_preflight" (get payload "action")))
          (is (= false (get payload "ok")))
          (is (pos? (get payload "failed_count")))
          (is (some #{"sync_status"} (get payload "failed_checks")))))))

  (let [cfg-path (.getPath (java.io.File/createTempFile "kimen-config" ".json"))]
    (.delete (java.io.File. cfg-path))

    (let [{:keys [exit-code stderr]}
          (run-cli ["sync" "preflight" "--json" "--only" "nope"] {} {:config-path cfg-path})]
      (is (= exit-code/code-sync-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"unknown_preflight_check\"")))

    (let [{:keys [exit-code stderr]}
          (run-cli ["sync" "preflight" "--json" "--only" "doctor" "--skip" "doctor"] {}
                   {:config-path cfg-path})]
      (is (= exit-code/code-sync-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"no_preflight_checks_selected\"")))))

(deftest sync-changes-analyzes-disjoint-and-conflicting-sets
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        vault-path (str (.getPath dir) "/vault.db")
        remote-vault-path (str (.getPath dir) "/remote-vault.db")
        id-path (str (.getPath dir) "/sync.agekey")
        remote-dir (str (.getPath dir) "/remote")
        pass-cmd "printf test-passphrase"]
    (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (run-cli ["secret" "set" "api_key" "--value" "a1" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
             {:config-path cfg-path})
    (run-cli ["secret" "set" "db_pw" "--value" "p1" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
             {:config-path cfg-path})
    (let [{:keys [stdout]} (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-path})
          keygen (json/read-str stdout)
          recipient (get keygen "recipient")]
      (run-cli ["sync" "init" "--remote" "origin" "--path" remote-dir "--identity" id-path "--recipient" recipient "--json"] {}
               {:config-path cfg-path})
      (let [{:keys [stdout]} (run-cli ["sync" "push" "--remote" "origin" "--passphrase-cmd" pass-cmd "--json"] {}
                                      {:config-path cfg-path})
            push-payload (json/read-str stdout)
            bundle-path (get push-payload "bundle_path")]
        (run-cli ["secret" "set" "api_key" "--value" "a2-local" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                 {:config-path cfg-path})
        (run-cli ["vault" "init" "--vault" remote-vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                 {:config-path cfg-path})
        (run-cli ["secret" "set" "api_key" "--value" "a1" "--vault" remote-vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                 {:config-path cfg-path})
        (run-cli ["secret" "set" "db_pw" "--value" "p2-remote" "--vault" remote-vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                 {:config-path cfg-path})
        (run-cli ["bundle" "seal" "--vault" remote-vault-path "--out" bundle-path "--recipient" recipient "--json"] {}
                 {:config-path cfg-path})

        (let [{:keys [exit-code stdout stderr]}
              (run-cli ["sync" "changes" "--remote" "origin" "--passphrase-cmd" pass-cmd "--json"] {}
                       {:config-path cfg-path})
              changes (json/read-str stdout)]
          (is (= 0 exit-code))
          (is (nil? stderr))
          (is (= "sync_changes" (get changes "action")))
          (is (= true (get changes "has_baseline")))
          (is (= true (get changes "can_reconcile")))
          (is (some #{"api_key"} (get changes "local_changed_keys")))
          (is (some #{"db_pw"} (get changes "remote_changed_keys")))
          (is (empty? (get changes "conflict_keys"))))

        (run-cli ["secret" "set" "api_key" "--value" "a3-remote" "--vault" remote-vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                 {:config-path cfg-path})
        (run-cli ["bundle" "seal" "--vault" remote-vault-path "--out" bundle-path "--recipient" recipient "--json"] {}
                 {:config-path cfg-path})

        (let [{:keys [exit-code stdout stderr]}
              (run-cli ["sync" "changes" "--remote" "origin" "--passphrase-cmd" pass-cmd "--json"] {}
                       {:config-path cfg-path})
              changes (json/read-str stdout)]
          (is (= 0 exit-code))
          (is (nil? stderr))
          (is (= false (get changes "can_reconcile")))
          (is (some #{"api_key"} (get changes "conflict_keys"))))))))

(deftest sync-resolve-remote-and-local-take
  (testing "remote take applies chosen key and leaves remote-only changes for pull"
    (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
          cfg-path (str (.getPath dir) "/config.json")
          vault-path (str (.getPath dir) "/vault.db")
          remote-vault-path (str (.getPath dir) "/remote-vault.db")
          id-path (str (.getPath dir) "/sync.agekey")
          remote-dir (str (.getPath dir) "/remote")
          pass-cmd "printf test-passphrase"]
      (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})
      (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
      (run-cli ["secret" "set" "api_key" "--value" "a1" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
               {:config-path cfg-path})
      (run-cli ["secret" "set" "db_pw" "--value" "p1" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
               {:config-path cfg-path})
      (let [{:keys [stdout]} (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-path})
            keygen (json/read-str stdout)
            recipient (get keygen "recipient")]
        (run-cli ["sync" "init" "--remote" "origin" "--path" remote-dir "--identity" id-path "--recipient" recipient "--json"] {}
                 {:config-path cfg-path})
        (let [{:keys [stdout]} (run-cli ["sync" "push" "--remote" "origin" "--passphrase-cmd" pass-cmd "--json"] {}
                                        {:config-path cfg-path})
              push-payload (json/read-str stdout)
              bundle-path (get push-payload "bundle_path")]
          (run-cli ["secret" "set" "api_key" "--value" "a2-local" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                   {:config-path cfg-path})
          (run-cli ["vault" "init" "--vault" remote-vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                   {:config-path cfg-path})
          (run-cli ["secret" "set" "api_key" "--value" "a3-remote" "--vault" remote-vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                   {:config-path cfg-path})
          (run-cli ["secret" "set" "db_pw" "--value" "p2-remote" "--vault" remote-vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                   {:config-path cfg-path})
          (run-cli ["bundle" "seal" "--vault" remote-vault-path "--out" bundle-path "--recipient" recipient "--json"] {}
                   {:config-path cfg-path})

          (let [{:keys [exit-code stdout stderr]}
                (run-cli ["sync" "resolve" "--remote" "origin" "--take" "remote" "--key" "api_key" "--passphrase-cmd" pass-cmd "--json"] {}
                         {:config-path cfg-path})
                resolve-payload (json/read-str stdout)]
            (is (= 0 exit-code))
            (is (nil? stderr))
            (is (= "sync_resolve" (get resolve-payload "action")))
            (is (= "remote" (get resolve-payload "take")))
            (is (= 1 (get resolve-payload "resolved_count")))
            (is (= 0 (get resolve-payload "remaining_conflict_count")))
            (is (= "sync_pull" (get resolve-payload "recommended_action"))))

          (let [{:keys [exit-code stdout]} (run-cli ["secret" "get" "api_key" "--unsafe-stdout" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                                                    {:config-path cfg-path})]
            (is (= 0 exit-code))
            (is (str/includes? stdout "\"value_b64\":\"YTMtcmVtb3Rl\"")))))))

  (testing "local take keeps local value and recommends push"
    (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
          cfg-path (str (.getPath dir) "/config.json")
          vault-path (str (.getPath dir) "/vault.db")
          remote-vault-path (str (.getPath dir) "/remote-vault.db")
          id-path (str (.getPath dir) "/sync.agekey")
          remote-dir (str (.getPath dir) "/remote")
          pass-cmd "printf test-passphrase"]
      (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})
      (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
      (run-cli ["secret" "set" "api_key" "--value" "a1" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
               {:config-path cfg-path})
      (let [{:keys [stdout]} (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-path})
            keygen (json/read-str stdout)
            recipient (get keygen "recipient")]
        (run-cli ["sync" "init" "--remote" "origin" "--path" remote-dir "--identity" id-path "--recipient" recipient "--json"] {}
                 {:config-path cfg-path})
        (let [{:keys [stdout]} (run-cli ["sync" "push" "--remote" "origin" "--passphrase-cmd" pass-cmd "--json"] {}
                                        {:config-path cfg-path})
              push-payload (json/read-str stdout)
              bundle-path (get push-payload "bundle_path")]
          (run-cli ["secret" "set" "api_key" "--value" "a2-local" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                   {:config-path cfg-path})
          (run-cli ["vault" "init" "--vault" remote-vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                   {:config-path cfg-path})
          (run-cli ["secret" "set" "api_key" "--value" "a3-remote" "--vault" remote-vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                   {:config-path cfg-path})
          (run-cli ["bundle" "seal" "--vault" remote-vault-path "--out" bundle-path "--recipient" recipient "--json"] {}
                   {:config-path cfg-path})

          (let [{:keys [exit-code stdout stderr]}
                (run-cli ["sync" "resolve" "--remote" "origin" "--take" "local" "--key" "api_key" "--passphrase-cmd" pass-cmd "--json"] {}
                         {:config-path cfg-path})
                resolve-payload (json/read-str stdout)]
            (is (= 0 exit-code))
            (is (nil? stderr))
            (is (= "local" (get resolve-payload "take")))
            (is (= "sync_push" (get resolve-payload "recommended_action"))))

          (let [{:keys [exit-code stdout]} (run-cli ["secret" "get" "api_key" "--unsafe-stdout" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                                                    {:config-path cfg-path})]
            (is (= 0 exit-code))
            (is (str/includes? stdout "\"value_b64\":\"YTItbG9jYWw=\""))))))))

(deftest sync-resolve-typed-errors
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        vault-path (str (.getPath dir) "/vault.db")
        remote-vault-path (str (.getPath dir) "/remote-vault.db")
        id-path (str (.getPath dir) "/sync.agekey")
        remote-dir (str (.getPath dir) "/remote")
        pass-cmd "printf test-passphrase"]
    (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (run-cli ["secret" "set" "api_key" "--value" "a1" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
             {:config-path cfg-path})
    (let [{:keys [stdout]} (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-path})
          keygen (json/read-str stdout)
          recipient (get keygen "recipient")]
      (run-cli ["sync" "init" "--remote" "origin" "--path" remote-dir "--identity" id-path "--recipient" recipient "--json"] {}
               {:config-path cfg-path})
      (run-cli ["sync" "push" "--remote" "origin" "--passphrase-cmd" pass-cmd "--json"] {}
               {:config-path cfg-path})

      (let [{:keys [exit-code stderr]}
            (run-cli ["sync" "resolve" "--remote" "origin" "--take" "invalid" "--passphrase-cmd" pass-cmd "--json"] {}
                     {:config-path cfg-path})]
        (is (= exit-code/code-sync-failed exit-code))
        (is (str/includes? stderr "\"reason\":\"invalid_take\"")))

      (let [{:keys [exit-code stderr]}
            (run-cli ["sync" "resolve" "--remote" "origin" "--take" "remote" "--passphrase-cmd" pass-cmd "--json"] {}
                     {:config-path cfg-path})]
        (is (= exit-code/code-sync-failed exit-code))
        (is (str/includes? stderr "\"reason\":\"no_overlapping_conflicts\"")))

      (let [{:keys [stdout]} (run-cli ["sync" "push" "--remote" "origin" "--passphrase-cmd" pass-cmd "--json"] {}
                                      {:config-path cfg-path})
            push-payload (json/read-str stdout)
            bundle-path (get push-payload "bundle_path")]
        (run-cli ["secret" "set" "api_key" "--value" "a2-local" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                 {:config-path cfg-path})
        (run-cli ["vault" "init" "--vault" remote-vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                 {:config-path cfg-path})
        (run-cli ["secret" "set" "api_key" "--value" "a3-remote" "--vault" remote-vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                 {:config-path cfg-path})
        (run-cli ["bundle" "seal" "--vault" remote-vault-path "--out" bundle-path "--recipient" recipient "--json"] {}
                 {:config-path cfg-path})

        (let [{:keys [exit-code stderr]}
              (run-cli ["sync" "resolve" "--remote" "origin" "--take" "remote" "--key" "does_not_exist" "--passphrase-cmd" pass-cmd "--json"] {}
                       {:config-path cfg-path})]
          (is (= exit-code/code-sync-failed exit-code))
          (is (str/includes? stderr "\"reason\":\"resolve_key_not_conflict\"")))))))

(deftest sync-git-remote-push-pull-roundtrip
  (if-not (git-available?)
    (is true)
    (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
          repo-path (str (.getPath dir) "/team.git")
          _ (run-git! nil "init" "--bare" repo-path)
          cfg-a (str (.getPath dir) "/config-a.json")
          vault-a (str (.getPath dir) "/vault-a.db")
          cfg-b (str (.getPath dir) "/config-b.json")
          vault-b (str (.getPath dir) "/vault-b.db")
          id-path (str (.getPath dir) "/sync.agekey")
          pass-cmd "printf test-passphrase"]
      (run-cli ["config" "vault" "set" vault-a "--json"] {} {:config-path cfg-a})
      (run-cli ["vault" "init" "--vault" vault-a "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-a})
      (run-cli ["secret" "set" "api_key" "--value" "team-v1" "--vault" vault-a "--passphrase-cmd" pass-cmd "--json"] {}
               {:config-path cfg-a})
      (let [{:keys [stdout]} (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-a})
            keygen (json/read-str stdout)
            recipient (get keygen "recipient")]
        (run-cli ["remote" "add" "origin"
                  "--type" "git"
                  "--path" repo-path
                  "--branch" "main"
                  "--bundle-path" "vault.age"
                  "--recipient" recipient
                  "--identity" id-path
                  "--json"] {}
                 {:config-path cfg-a})
        (let [{:keys [exit-code stdout stderr]} (run-cli ["sync" "push" "--json"] {} {:config-path cfg-a})
              payload (json/read-str stdout)]
          (is (= 0 exit-code))
          (is (nil? stderr))
          (is (= "sync_push" (get payload "action")))
          (is (= "git" (get payload "remote_type"))))

        (run-cli ["config" "vault" "set" vault-b "--json"] {} {:config-path cfg-b})
        (run-cli ["remote" "add" "origin"
                  "--type" "git"
                  "--path" repo-path
                  "--branch" "main"
                  "--bundle-path" "vault.age"
                  "--recipient" recipient
                  "--identity" id-path
                  "--json"] {}
                 {:config-path cfg-b})
        (let [{:keys [exit-code stdout stderr]} (run-cli ["sync" "pull" "--json"] {} {:config-path cfg-b})
              payload (json/read-str stdout)]
          (is (= 0 exit-code))
          (is (nil? stderr))
          (is (= "sync_pull" (get payload "action")))
          (is (= "git" (get payload "remote_type"))))
        (let [{:keys [exit-code stdout]} (run-cli ["secret" "get" "api_key" "--unsafe-stdout" "--vault" vault-b "--passphrase-cmd" pass-cmd "--json"] {}
                                                  {:config-path cfg-b})]
          (is (= 0 exit-code))
          (is (str/includes? stdout "\"value_b64\":\"dGVhbS12MQ==\"")))))))

(deftest sync-git-remote-pull-dry-run-no-mutation
  (if-not (git-available?)
    (is true)
    (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
          repo-path (str (.getPath dir) "/team.git")
          _ (run-git! nil "init" "--bare" repo-path)
          cfg-a (str (.getPath dir) "/config-a.json")
          vault-a (str (.getPath dir) "/vault-a.db")
          cfg-b (str (.getPath dir) "/config-b.json")
          vault-b (str (.getPath dir) "/vault-b.db")
          id-path (str (.getPath dir) "/sync.agekey")
          pass-cmd "printf test-passphrase"]
      (run-cli ["config" "vault" "set" vault-a "--json"] {} {:config-path cfg-a})
      (run-cli ["vault" "init" "--vault" vault-a "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-a})
      (run-cli ["secret" "set" "api_key" "--value" "team-v1" "--vault" vault-a "--passphrase-cmd" pass-cmd "--json"] {}
               {:config-path cfg-a})
      (let [{:keys [stdout]} (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-a})
            keygen (json/read-str stdout)
            recipient (get keygen "recipient")]
        (run-cli ["remote" "add" "origin"
                  "--type" "git"
                  "--path" repo-path
                  "--branch" "main"
                  "--bundle-path" "vault.age"
                  "--recipient" recipient
                  "--identity" id-path
                  "--json"] {}
                 {:config-path cfg-a})
        (run-cli ["sync" "push" "--json"] {} {:config-path cfg-a})

        (run-cli ["config" "vault" "set" vault-b "--json"] {} {:config-path cfg-b})
        (run-cli ["remote" "add" "origin"
                  "--type" "git"
                  "--path" repo-path
                  "--branch" "main"
                  "--bundle-path" "vault.age"
                  "--recipient" recipient
                  "--identity" id-path
                  "--json"] {}
                 {:config-path cfg-b})
        (let [{:keys [exit-code stdout stderr]} (run-cli ["sync" "pull" "--dry-run" "--json"] {} {:config-path cfg-b})
              payload (json/read-str stdout)]
          (is (= 0 exit-code))
          (is (nil? stderr))
          (is (= "sync_pull_dry_run" (get payload "action")))
          (is (= "git" (get payload "remote_type")))
          (is (= true (get payload "dry_run")))
          (is (= false (get payload "has_local")))
          (is (= false (get payload "would_backup"))))
        (is (= false (.exists (io/file vault-b))))
        (let [cfg (json/read-str (slurp cfg-b))]
          (is (nil? (get-in cfg ["sync" "origin"]))))))))

(deftest sync-git-remote-push-dry-run-no-mutation
  (if-not (git-available?)
    (is true)
    (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
          repo-path (str (.getPath dir) "/team.git")
          _ (run-git! nil "init" "--bare" repo-path)
          cfg-path (str (.getPath dir) "/config.json")
          vault-path (str (.getPath dir) "/vault.db")
          id-path (str (.getPath dir) "/sync.agekey")
          pass-cmd "printf test-passphrase"]
      (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})
      (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
      (run-cli ["secret" "set" "api_key" "--value" "v1" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
               {:config-path cfg-path})
      (let [{:keys [stdout]} (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-path})
            keygen (json/read-str stdout)
            recipient (get keygen "recipient")]
        (run-cli ["remote" "add" "origin"
                  "--type" "git"
                  "--path" repo-path
                  "--branch" "main"
                  "--bundle-path" "vault.age"
                  "--recipient" recipient
                  "--identity" id-path
                  "--json"] {}
                 {:config-path cfg-path})
        (run-cli ["sync" "push" "--json"] {} {:config-path cfg-path})
        (let [cfg-before (json/read-str (slurp cfg-path))
              baseline-before (get-in cfg-before ["sync" "origin" "last_seen_rev"])
              {:keys [stdout]} (run-cli ["sync" "status" "--json"] {} {:config-path cfg-path})
              status-before (json/read-str stdout)
              remote-rev-before (get status-before "remote_rev")]
          (run-cli ["secret" "set" "api_key" "--value" "v2" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                   {:config-path cfg-path})
          (let [{:keys [exit-code stdout stderr]} (run-cli ["sync" "push" "--dry-run" "--json"] {} {:config-path cfg-path})
                payload (json/read-str stdout)]
            (is (= 0 exit-code))
            (is (nil? stderr))
            (is (= "sync_push_dry_run" (get payload "action")))
            (is (= "git" (get payload "remote_type")))
            (is (= true (get payload "dry_run")))
            (is (= true (get payload "can_push")))
            (is (= true (get payload "has_local"))))
          (let [cfg-after (json/read-str (slurp cfg-path))
                baseline-after (get-in cfg-after ["sync" "origin" "last_seen_rev"])
                {:keys [stdout]} (run-cli ["sync" "status" "--json"] {} {:config-path cfg-path})
                status-after (json/read-str stdout)]
            (is (= baseline-before baseline-after))
            (is (= remote-rev-before (get status-after "remote_rev")))))))))

(deftest sync-git-remote-push-conflict-when-remote-changed
  (if-not (git-available?)
    (is true)
    (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
          repo-path (str (.getPath dir) "/team.git")
          _ (run-git! nil "init" "--bare" repo-path)
          cfg-a (str (.getPath dir) "/config-a.json")
          vault-a (str (.getPath dir) "/vault-a.db")
          cfg-b (str (.getPath dir) "/config-b.json")
          vault-b (str (.getPath dir) "/vault-b.db")
          id-path (str (.getPath dir) "/sync.agekey")
          pass-cmd "printf test-passphrase"]
      (run-cli ["config" "vault" "set" vault-a "--json"] {} {:config-path cfg-a})
      (run-cli ["vault" "init" "--vault" vault-a "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-a})
      (run-cli ["secret" "set" "api_key" "--value" "a1" "--vault" vault-a "--passphrase-cmd" pass-cmd "--json"] {}
               {:config-path cfg-a})
      (let [{:keys [stdout]} (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-a})
            keygen (json/read-str stdout)
            recipient (get keygen "recipient")]
        (run-cli ["remote" "add" "origin"
                  "--type" "git"
                  "--path" repo-path
                  "--branch" "main"
                  "--bundle-path" "vault.age"
                  "--recipient" recipient
                  "--identity" id-path
                  "--json"] {}
                 {:config-path cfg-a})
        (run-cli ["sync" "push" "--json"] {} {:config-path cfg-a})
        (run-cli ["secret" "set" "api_key" "--value" "a2" "--vault" vault-a "--passphrase-cmd" pass-cmd "--json"] {}
                 {:config-path cfg-a})

        (run-cli ["config" "vault" "set" vault-b "--json"] {} {:config-path cfg-b})
        (run-cli ["remote" "add" "origin"
                  "--type" "git"
                  "--path" repo-path
                  "--branch" "main"
                  "--bundle-path" "vault.age"
                  "--recipient" recipient
                  "--identity" id-path
                  "--json"] {}
                 {:config-path cfg-b})
        (run-cli ["sync" "pull" "--json"] {} {:config-path cfg-b})
        (run-cli ["secret" "set" "api_key" "--value" "b2" "--vault" vault-b "--passphrase-cmd" pass-cmd "--json"] {}
                 {:config-path cfg-b})
        (run-cli ["sync" "push" "--json"] {} {:config-path cfg-b})

        (let [{:keys [exit-code stderr]} (run-cli ["sync" "push" "--json"] {} {:config-path cfg-a})
              err (json/read-str stderr)]
          (is (= exit-code/code-sync-conflict exit-code))
          (is (= "remote_changed" (get err "reason")))
          (is (= "sync_pull" (get err "recommended_action"))))))))

(deftest sync-git-remote-rejects-lock-flags
  (if-not (git-available?)
    (is true)
    (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
          repo-path (str (.getPath dir) "/team.git")
          _ (run-git! nil "init" "--bare" repo-path)
          cfg-path (str (.getPath dir) "/config.json")
          vault-path (str (.getPath dir) "/vault.db")
          id-path (str (.getPath dir) "/sync.agekey")
          pass-cmd "printf test-passphrase"]
      (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})
      (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
      (run-cli ["secret" "set" "api_key" "--value" "v1" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
               {:config-path cfg-path})
      (let [{:keys [stdout]} (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-path})
            keygen (json/read-str stdout)
            recipient (get keygen "recipient")]
        (run-cli ["remote" "add" "origin"
                  "--type" "git"
                  "--path" repo-path
                  "--recipient" recipient
                  "--identity" id-path
                  "--json"] {}
                 {:config-path cfg-path})
        (let [{:keys [exit-code stderr]} (run-cli ["sync" "push" "--lock-wait" "1s" "--json"] {}
                                                  {:config-path cfg-path})
              err (json/read-str stderr)]
          (is (= exit-code/code-sync-failed exit-code))
          (is (= exit-code/code-sync-failed (get err "exit_code"))))))))

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

(deftest bundle-cli-roundtrip-and-typed-errors
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        vault-path (str (.getPath dir) "/vault.db")
        cfg-path (str (.getPath dir) "/config.json")
        id-path (str (.getPath dir) "/bundle.agekey")
        bundle-path (str (.getPath dir) "/vault.age")
        out-vault (str (.getPath dir) "/vault.out.db")
        pass-cmd "printf test-passphrase"]
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})

    (let [{:keys [exit-code stdout stderr]} (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-path})
          payload (json/read-str stdout)
          recipient (get payload "recipient")]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (= "bundle_keygen" (get payload "action")))
      (is (= id-path (get payload "identity_path")))
      (is (string? recipient))
      (is (str/starts-with? recipient "age1")))

    (let [{:keys [exit-code stdout stderr]} (run-cli ["bundle" "recipient" "--identity" id-path "--json"] {} {:config-path cfg-path})
          payload (json/read-str stdout)
          recipient (get payload "recipient")]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (= "bundle_recipient" (get payload "action")))
      (is (str/starts-with? recipient "age1"))

      (let [{:keys [exit-code stderr]}
            (run-cli ["bundle" "seal" "--vault" vault-path "--out" bundle-path "--recipient" "not-age" "--json"] {}
                     {:config-path cfg-path})]
        (is (= exit-code/code-bundle-failed exit-code))
        (is (str/includes? stderr "\"reason\":\"invalid_recipient\"")))

      (let [{:keys [exit-code stdout stderr]}
            (run-cli ["bundle" "seal" "--vault" vault-path "--out" bundle-path "--recipient" recipient "--json"] {}
                     {:config-path cfg-path})
            payload (json/read-str stdout)]
        (is (= 0 exit-code))
        (is (nil? stderr))
        (is (= "bundle_seal" (get payload "action")))
        (is (= bundle-path (get payload "out"))))

      (let [{:keys [exit-code stdout stderr]}
            (run-cli ["bundle" "open" "--in" bundle-path "--out-vault" out-vault "--identity" id-path "--json"] {}
                     {:config-path cfg-path})
            payload (json/read-str stdout)]
        (is (= 0 exit-code))
        (is (nil? stderr))
        (is (= "bundle_open" (get payload "action")))
        (is (= out-vault (get payload "out_vault"))))

      (is (java.util.Arrays/equals
           (java.nio.file.Files/readAllBytes (.toPath (java.io.File. vault-path)))
           (java.nio.file.Files/readAllBytes (.toPath (java.io.File. out-vault)))))

      (let [{:keys [exit-code stderr]}
            (run-cli ["bundle" "open" "--in" bundle-path "--out-vault" out-vault "--identity" id-path "--json"] {}
                     {:config-path cfg-path})]
        (is (= exit-code/code-bundle-failed exit-code))
        (is (str/includes? stderr "\"reason\":\"output_vault_exists\""))))

    (let [{:keys [exit-code stderr]} (run-cli ["bundle" "open" "--json"] {} {:config-path cfg-path})]
      (is (= exit-code/code-bundle-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"missing_in\"")))

    (let [{:keys [exit-code stderr]} (run-cli ["bundle" "keygen" "--out" id-path "--json"] {} {:config-path cfg-path})]
      (is (= exit-code/code-bundle-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"identity_exists\"")))))

(deftest bundle-recipient-supports-identity-stdin
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        id-path (str (.getPath dir) "/bundle.agekey")]
    (run-cli ["bundle" "keygen" "--out" id-path "--json"] {})
    (let [identity-src (slurp id-path)
          {:keys [exit-code stdout stderr]}
          (run-cli ["bundle" "recipient" "--identity-stdin" "--json"]
                   {}
                   {:stdin (java.io.StringReader. identity-src)})
          payload (json/read-str stdout)]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (= "bundle_recipient" (get payload "action")))
      (is (str/starts-with? (get payload "recipient") "age1")))))

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

(deftest envfile-encodes-shell-unsafe-values
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        vault-path (str (.getPath dir) "/vault.db")
        cfg-path (str (.getPath dir) "/config.json")
        envfile-path (str (.getPath dir) "/app.env")
        pass-cmd "printf test-passphrase"]
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})

    (let [{:keys [exit-code stdout]}
          (run-cli ["envfile" "--env" "MESSAGE=const:hello world" "--out" envfile-path "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"]
                   {}
                   {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"action\":\"envfile\""))
      (is (str/includes? (slurp envfile-path) "MESSAGE=\"hello world\"")))))

(deftest envfile-rejects-newline-values
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        vault-path (str (.getPath dir) "/vault.db")
        cfg-path (str (.getPath dir) "/config.json")
        map-path (str (.getPath dir) "/newline.kmap")
        envfile-path (str (.getPath dir) "/app.env")
        pass-cmd "printf test-passphrase"]
    (spit map-path "env MULTI=multiline\n")
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (run-cli ["secret" "set" "multiline" "--value" "line1\nline2" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
             {:config-path cfg-path})

    (let [{:keys [exit-code stderr]}
          (run-cli ["envfile" "--map" map-path "--out" envfile-path "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {}
                   {:config-path cfg-path})]
      (is (= exit-code/code-envfile-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"envfile_failed\""))
      (is (str/includes? stderr "value contains newline")))))

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

(deftest project-command-help-and-unknown-subcommand
  (let [{:keys [exit-code stdout stderr]} (run-cli ["project"] {})]
    (is (= 0 exit-code))
    (is (nil? stderr))
    (is (str/includes? stdout "kimen project"))
    (is (str/includes? stdout "project run")))

  (let [{:keys [exit-code stderr]} (run-cli ["project" "nope"] {})]
    (is (= 1 exit-code))
    (is (str/includes? stderr "unknown project command"))))

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

(deftest doctor-json-ok-and-missing-vault
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        vault-path (str (.getPath dir) "/vault.db")
        map-path (str (.getPath dir) "/app.kmap")
        pass-cmd "printf test-passphrase"]
    (spit map-path "env API_KEY=api_key\n")
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})

    (let [{:keys [exit-code stdout stderr]} (run-cli ["doctor" "--map" map-path "--json"] {}
                                                     {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (nil? stderr))
      (is (str/includes? stdout "\"action\":\"doctor\""))
      (is (str/includes? stdout "\"ok\":true")))

    (let [missing (str (.getPath dir) "/missing-vault.db")]
      (run-cli ["config" "vault" "set" missing "--json"] {} {:config-path cfg-path})
      (let [{:keys [exit-code stdout]} (run-cli ["doctor" "--json"] {} {:config-path cfg-path})]
        (is (= exit-code/code-doctor-failed exit-code))
        (is (str/includes? stdout "\"ok\":false"))
        (is (str/includes? stdout "\"name\":\"vault_file\""))
        (is (str/includes? stdout "\"status\":\"error\"")))
      (let [{:keys [exit-code stdout]} (run-cli ["doctor" "--allow-missing-vault" "--json"] {} {:config-path cfg-path})]
        (is (= 0 exit-code))
        (is (str/includes? stdout "\"ok\":true"))))))

(deftest doctor-strict-fails-on-warnings
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        vault-path (str (.getPath dir) "/vault.db")
        map-path (str (.getPath dir) "/warn.kmap")
        pass-cmd "printf test-passphrase"]
    (spit map-path "file conf/api.txt=api_key\n")
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})

    (let [{:keys [exit-code stdout]} (run-cli ["doctor" "--map" map-path "--strict" "--json"] {}
                                              {:config-path cfg-path})]
      (is (= exit-code/code-doctor-failed exit-code))
      (is (str/includes? stdout "\"ok\":false"))
      (is (str/includes? stdout "\"warning_count\":")))))

(deftest init-ci-pr-safety-json-writes-workflow
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        out-path (str (.getPath dir) "/.github/workflows/kimen-pr-safety.yml")
        {:keys [exit-code stdout stderr]}
        (run-cli ["init" "ci-pr-safety"
                  "--out" out-path
                  "--profile" "qa"
                  "--command" "echo lint-check"
                  "--json"]
                 {})]
    (is (= 0 exit-code))
    (is (nil? stderr))
    (is (str/includes? stdout "\"action\":\"init_ci_pr_safety\""))
    (is (str/includes? stdout out-path))
    (let [body (slurp out-path)]
      (is (str/includes? body "name: kimen-pr-safety"))
      (is (str/includes? body "default: \"qa\""))
      (is (str/includes? body "default: \"echo lint-check\"")))))

(deftest init-ci-sync-gate-errors-and-force-overwrite
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        out-path (str (.getPath dir) "/kimen-sync-gate.yml")]
    (let [{:keys [exit-code stderr]}
          (run-cli ["init" "ci-sync-gate" "--out" out-path "--remote-type" "http" "--json"] {})]
      (is (= exit-code/code-init-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"invalid_remote_type\"")))

    (spit out-path "existing\n")
    (let [{:keys [exit-code stderr]}
          (run-cli ["init" "ci-sync-gate" "--out" out-path "--json"] {})]
      (is (= exit-code/code-init-failed exit-code))
      (is (str/includes? stderr "\"reason\":\"output_exists\"")))

    (let [{:keys [exit-code stdout]}
          (run-cli ["init" "ci-sync-gate" "--out" out-path "--force" "--json"] {})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"action\":\"init_ci_sync_gate\""))
      (is (str/includes? (slurp out-path) "name: kimen-sync-gate")))))

(deftest init-ci-deploy-json-writes-workflow
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        out-path (str (.getPath dir) "/.github/workflows/kimen-deploy.yml")
        {:keys [exit-code stdout stderr]}
        (run-cli ["init" "ci-deploy"
                  "--out" out-path
                  "--profile" "stage"
                  "--deploy-command" "./scripts/release.sh --dry-run"
                  "--json"]
                 {})]
    (is (= 0 exit-code))
    (is (nil? stderr))
    (is (str/includes? stdout "\"action\":\"init_ci_deploy\""))
    (let [body (slurp out-path)]
      (is (str/includes? body "name: kimen-deploy"))
      (is (str/includes? body "default: \"stage\""))
      (is (str/includes? body "default: \"./scripts/release.sh --dry-run\""))
      (is (str/includes? body "./kimen project run --profile")))))

(deftest doctor-bundle-identity-checks
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        vault-path (str (.getPath dir) "/vault.db")
        bundle-path (str (.getPath dir) "/vault.age")
        id-path (str (.getPath dir) "/ci.agekey")
        pass-cmd "printf test-passphrase"]
    (spit bundle-path "fake-bundle")
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (run-cli ["config" "vault" "set" vault-path "--json"] {} {:config-path cfg-path})

    (let [{:keys [exit-code stdout]}
          (run-cli ["doctor" "--bundle-in" bundle-path "--json"] {} {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"name\":\"bundle_decrypt\""))
      (is (str/includes? stdout "\"status\":\"warning\"")))

    (spit id-path "not-an-age-identity\n")
    (let [{:keys [exit-code stdout]}
          (run-cli ["doctor" "--bundle-in" bundle-path "--identity" id-path "--json"] {} {:config-path cfg-path})]
      (is (= exit-code/code-doctor-failed exit-code))
      (is (str/includes? stdout "\"name\":\"bundle_identity\""))
      (is (str/includes? stdout "\"status\":\"error\"")))))

(deftest doctor-remote-checks
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        vault-path (str (.getPath dir) "/vault.db")
        missing-fs (str (.getPath dir) "/remote-missing")
        pass-cmd "printf test-passphrase"]
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (spit cfg-path
          (format "{\"vault\":{\"path\":%s},\"remotes\":[{\"name\":\"origin\",\"type\":\"fs\",\"path\":%s}]}\n"
                  (pr-str vault-path)
                  (pr-str missing-fs)))

    (let [{:keys [exit-code stdout]} (run-cli ["doctor" "--json"] {} {:config-path cfg-path})]
      (is (= 0 exit-code))
      (is (str/includes? stdout "\"name\":\"remote_origin_fs_dir\""))
      (is (str/includes? stdout "\"status\":\"warning\"")))

    (let [{:keys [exit-code stdout]} (run-cli ["doctor" "--strict" "--json"] {} {:config-path cfg-path})]
      (is (= exit-code/code-doctor-failed exit-code))
      (is (str/includes? stdout "\"ok\":false"))))

  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-clj-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath dir) "/config.json")
        vault-path (str (.getPath dir) "/vault.db")
        missing-git (str (.getPath dir) "/missing.git")
        pass-cmd "printf test-passphrase"]
    (run-cli ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"] {} {:config-path cfg-path})
    (spit cfg-path
          (format "{\"vault\":{\"path\":%s},\"remotes\":[{\"name\":\"origin\",\"type\":\"git\",\"path\":%s}]}\n"
                  (pr-str vault-path)
                  (pr-str missing-git)))

    (let [{:keys [exit-code stdout]} (run-cli ["doctor" "--json"] {} {:config-path cfg-path})]
      (is (= exit-code/code-doctor-failed exit-code))
      (is (str/includes? stdout "\"name\":\"remote_origin_git_remote\""))
      (is (str/includes? stdout "\"status\":\"error\"")))))

(deftest init-template-render-defaults-match-checked-in-workflows
  (let [pr-workflow (slurp ".github/workflows/kimen-pr-safety-template.yml")
        deploy-workflow (slurp ".github/workflows/kimen-deploy-template.yml")
        sync-workflow (slurp ".github/workflows/kimen-sync-gate-template.yml")]
    (is (= (normalize-newlines pr-workflow)
           (normalize-newlines
            (init/render-ci-pr-safety-workflow
             (init/default-ci-pr-safety-options)
             "kimen-pr-safety-template"))))
    (is (= (normalize-newlines deploy-workflow)
           (normalize-newlines
            (init/render-ci-deploy-workflow
             (init/default-ci-deploy-options)
             "kimen-deploy-template"))))
    (is (= (normalize-newlines sync-workflow)
           (normalize-newlines
            (init/render-ci-sync-gate-workflow
             (init/default-ci-sync-gate-options)
             "kimen-sync-gate-template"))))))

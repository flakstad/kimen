#!/usr/bin/env bb
(ns scripts.cli-integration
  (:require
   [babashka.process :as p]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [kimen.json :as json])
  (:import
   [java.nio.file Files]
   [java.util Arrays]))

(defn- fail!
  [message data]
  (throw (ex-info message data)))

(defn- ensure!
  [pred message data]
  (when-not pred
    (fail! message data)))

(defn- parse-json
  [s]
  (json/read-str s))

(defn- run-kimen
  [repo-root env-overrides argv & [stdin]]
  (let [env (merge (into {} (System/getenv)) env-overrides)
        opts (cond-> {:continue true
                      :out :string
                      :err :string
                      :dir repo-root
                      :env env}
               (some? stdin) (assoc :in stdin))
        res (apply p/sh opts "./bin/kimen" argv)]
    (assoc res :argv (vec argv))))

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
  (zero? (:exit (p/sh {:continue true :out :string :err :string} "git" "--version"))))

(defn- run-git!
  [argv]
  (let [res (apply p/sh {:continue true
                         :out :string
                         :err :string
                         :env (sanitized-git-env)}
                   "git"
                   argv)]
    (ensure! (zero? (:exit res)) "git command failed" {:argv argv :res res})
    res))

(defn- expect-success-json!
  [res action]
  (ensure! (zero? (:exit res)) "command failed" {:res res})
  (let [payload (parse-json (:out res))]
    (ensure! (= action (get payload "action")) "unexpected action" {:expected action :payload payload :res res})
    (ensure! (= 0 (get payload "exit_code")) "expected exit_code=0 in payload" {:payload payload :res res})
    payload))

(defn- expect-error-json!
  [res exit-code reason]
  (ensure! (= exit-code (:exit res)) "unexpected command exit code" {:expected exit-code :res res})
  (let [payload (parse-json (:err res))]
    (ensure! (= false (get payload "ok")) "expected error payload" {:payload payload :res res})
    (ensure! (= exit-code (get payload "exit_code")) "unexpected payload exit code" {:payload payload :res res})
    (ensure! (= reason (get payload "reason")) "unexpected payload reason" {:expected reason :payload payload :res res})
    payload))

(defn- expect-sync-report-error!
  [res exit-code reason]
  (ensure! (= exit-code (:exit res)) "unexpected sync report exit code" {:expected exit-code :res res})
  (ensure! (or (nil? (:err res)) (str/blank? (:err res))) "expected sync report errors on stdout only" {:res res})
  (let [payload (parse-json (:out res))]
    (ensure! (= "sync" (get payload "action")) "expected sync action in report" {:payload payload :res res})
    (ensure! (= "blocked" (get payload "decision")) "expected blocked sync report decision" {:payload payload :res res})
    (ensure! (= exit-code (get payload "exit_code")) "unexpected sync report payload exit code" {:payload payload :res res})
    (ensure! (= reason (get payload "reason")) "unexpected sync report reason" {:expected reason :payload payload :res res})
    payload))

(defn main!
  []
  (let [repo-root (.getCanonicalPath (io/file "."))
        kimen-bin (io/file repo-root "bin" "kimen")
        _ (ensure! (.exists kimen-bin) "missing bin/kimen script" {:repo-root repo-root})
        temp-dir (.toFile (Files/createTempDirectory "kimen-cli-integration" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg-path (str (.getPath temp-dir) "/config.json")
        vault-path (str (.getPath temp-dir) "/vault.db")
        id-path (str (.getPath temp-dir) "/bundle.agekey")
        bundle-path (str (.getPath temp-dir) "/vault.age")
        opened-vault (str (.getPath temp-dir) "/opened.vault.db")
        map-path (str (.getPath temp-dir) "/app.kmap")
        render-dir (str (.getPath temp-dir) "/render")
        envfile-path (str (.getPath temp-dir) "/app.env")
        workflow-pr (str (.getPath temp-dir) "/kimen-pr-safety.yml")
        workflow-deploy (str (.getPath temp-dir) "/kimen-deploy.yml")
        workflow-sync (str (.getPath temp-dir) "/kimen-sync-gate.yml")
        base-env {"KIMEN_CONFIG" cfg-path
                  "KIMEN_VAULT" vault-path}
        pass-cmd "printf integration-passphrase"
        _ (spit map-path "env API_KEY=api_key\nfile conf/api.txt=api_key\nenvpath API_KEY_PATH=conf/api.txt\n")]
    (expect-success-json! (run-kimen repo-root base-env ["version" "--json"]) "version")
    (expect-success-json! (run-kimen repo-root base-env ["config" "path" "--json"]) "config_path")

    (expect-success-json! (run-kimen repo-root base-env ["vault" "init" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"]) "vault_init")
    (expect-success-json! (run-kimen repo-root base-env ["vault" "info" "--vault" vault-path "--json"]) "vault_info")
    (expect-success-json! (run-kimen repo-root base-env ["vault" "path" "--json"]) "vault_path")

    (expect-success-json! (run-kimen repo-root base-env ["secret" "set" "api_key" "--value" "shh" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"]) "set")
    (expect-success-json! (run-kimen repo-root base-env ["secret" "list" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"]) "list")
    (expect-success-json! (run-kimen repo-root base-env ["secret" "get" "api_key" "--unsafe-stdout" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"]) "get")

    (expect-success-json! (run-kimen repo-root base-env ["map" "lint" "--map" map-path "--json"]) "map_lint")
    (expect-success-json! (run-kimen repo-root base-env ["plan" "--map" map-path "--json" "--" "echo" "ok"]) "plan")
    (expect-success-json! (run-kimen repo-root base-env ["run" "--map" map-path "--json" "--dry-run" "--" "echo" "ok"]) "plan")
    (expect-success-json! (run-kimen repo-root base-env ["render" "--map" map-path "--dir" render-dir "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"]) "render")
    (expect-success-json! (run-kimen repo-root base-env ["envfile" "--map" map-path "--out" envfile-path "--files-dir" render-dir "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"]) "envfile")
    (expect-success-json! (run-kimen repo-root base-env ["project" "plan" "--map" map-path "--json" "--" "echo" "ok"]) "plan")
    (expect-success-json! (run-kimen repo-root base-env ["project" "run" "--map" map-path "--json" "--dry-run" "--" "echo" "ok"]) "plan")
    (expect-success-json! (run-kimen repo-root base-env ["project" "render" "--map" map-path "--dir" (str (.getPath temp-dir) "/project-render") "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"]) "render")
    (expect-success-json! (run-kimen repo-root base-env ["secret" "mv" "api_key" "api_key2" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"]) "mv")
    (expect-success-json! (run-kimen repo-root base-env ["secret" "rm" "api_key2" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"]) "rm")

    (let [keygen (expect-success-json! (run-kimen repo-root base-env ["bundle" "keygen" "--out" id-path "--json"]) "bundle_keygen")
          recipient (get keygen "recipient")]
      (ensure! (string? recipient) "missing bundle recipient" {:keygen keygen})
      (expect-success-json! (run-kimen repo-root base-env ["bundle" "recipient" "--identity" id-path "--json"]) "bundle_recipient")
      (expect-success-json! (run-kimen repo-root base-env ["bundle" "seal" "--vault" vault-path "--out" bundle-path "--recipient" recipient "--json"]) "bundle_seal")
      (expect-success-json! (run-kimen repo-root base-env ["bundle" "open" "--in" bundle-path "--out-vault" opened-vault "--identity" id-path "--json"]) "bundle_open")
      (ensure! (Arrays/equals
                (Files/readAllBytes (.toPath (java.io.File. vault-path)))
                (Files/readAllBytes (.toPath (java.io.File. opened-vault))))
               "opened bundle vault differs from source vault"
               {:vault vault-path :opened opened-vault})
      (expect-error-json! (run-kimen repo-root base-env ["bundle" "open" "--json"]) 25 "missing_in")

      (expect-success-json! (run-kimen repo-root base-env ["remote" "add" "origin" "--path" (str (.getPath temp-dir) "/remote-a") "--identity" id-path "--json"]) "remote_add")
      (expect-success-json! (run-kimen repo-root base-env ["remote" "get" "origin" "--json"]) "remote_get")
      (expect-success-json! (run-kimen repo-root base-env ["remote" "set" "origin" "--path" (str (.getPath temp-dir) "/remote-b") "--json"]) "remote_set")
      (expect-success-json! (run-kimen repo-root base-env ["remote" "list" "--json"]) "remote_list")
      (expect-success-json! (run-kimen repo-root base-env ["remote" "rm" "origin" "--json"]) "remote_rm")
      (expect-error-json! (run-kimen repo-root base-env ["remote" "add" "bad/name" "--path" (str (.getPath temp-dir) "/remote-c") "--json"]) 30 "invalid_remote_name")

      (expect-success-json! (run-kimen repo-root base-env ["sync" "init" "--remote" "team" "--path" (str (.getPath temp-dir) "/sync-remote-a") "--identity" id-path "--recipient" recipient "--json"]) "sync_init")
      (expect-error-json! (run-kimen repo-root base-env ["sync" "init" "--remote" "team" "--path" (str (.getPath temp-dir) "/sync-remote-a") "--json"]) 32 "remote_exists")
      (expect-success-json! (run-kimen repo-root base-env ["sync" "init" "--remote" "team" "--update" "--path" (str (.getPath temp-dir) "/sync-remote-b") "--recipient" recipient "--json"]) "sync_init")
      (expect-success-json! (run-kimen repo-root base-env ["sync" "status" "--remote" "team" "--json"]) "sync_status")
      (let [sync-push (expect-success-json! (run-kimen repo-root base-env ["sync" "push" "--remote" "team" "--passphrase-cmd" pass-cmd "--json"]) "sync_push")
            sync-bundle-path (get sync-push "bundle_path")
            remote-vault (str (.getPath temp-dir) "/remote-writer.vault.db")]
        (ensure! (string? sync-bundle-path) "missing sync bundle path" {:sync-push sync-push})
        (let [pull-dry-run (expect-success-json! (run-kimen repo-root base-env ["sync" "pull" "--remote" "team" "--dry-run" "--json"]) "sync_pull_dry_run")
              pull-no-backup (expect-success-json! (run-kimen repo-root base-env ["sync" "pull" "--remote" "team" "--no-backup" "--json"]) "sync_pull")]
          (ensure! (= true (get pull-dry-run "would_backup")) "expected would_backup=true on sync pull dry-run with local vault" {:pull-dry-run pull-dry-run})
          (ensure! (nil? (get pull-no-backup "backup_path")) "expected nil backup_path for sync pull --no-backup" {:pull-no-backup pull-no-backup}))
        (let [preflight (expect-success-json! (run-kimen repo-root base-env ["sync" "preflight" "--remote" "team" "--json"]) "sync_preflight")
              lock-path (str sync-bundle-path ".lock")]
          (ensure! (= true (get preflight "ok")) "expected sync preflight success after initial push" {:preflight preflight})
          (spit lock-path "held\n")
          (let [unlock (expect-success-json! (run-kimen repo-root base-env ["sync" "unlock" "--remote" "team" "--yes" "--json"]) "sync_unlock")
                unlock-missing (expect-success-json! (run-kimen repo-root base-env ["sync" "unlock" "--remote" "team" "--yes" "--json"]) "sync_unlock")]
            (ensure! (= true (get unlock "removed")) "expected sync unlock to remove lock file" {:unlock unlock})
            (ensure! (= false (get unlock-missing "removed")) "expected sync unlock missing lock response" {:unlock-missing unlock-missing}))
          (spit lock-path "stale\n")
          (.setLastModified (io/file lock-path) (- (System/currentTimeMillis) (* 2 60 1000)))
          (let [stale-break (expect-success-json! (run-kimen repo-root base-env ["sync" "push" "--remote" "team" "--break-stale-lock-after" "1m" "--passphrase-cmd" pass-cmd "--json"]) "sync_push")]
            (ensure! (= true (get stale-break "stale_lock_broken")) "expected stale_lock_broken=true for stale lock push" {:stale-break stale-break})))
        (spit sync-bundle-path "tampered-remote\n")
        (expect-error-json! (run-kimen repo-root base-env ["sync" "push" "--remote" "team" "--json"]) 31 "remote_changed")
        (let [to-remote (expect-success-json! (run-kimen repo-root base-env ["sync" "reset-baseline" "--remote" "team" "--to-remote" "--yes" "--json"]) "sync_reset_baseline")
              rev-reset (expect-success-json! (run-kimen repo-root base-env ["sync" "reset-baseline" "--remote" "team" "--rev" (get to-remote "new_rev") "--yes" "--json"]) "sync_reset_baseline")
              reset-push (expect-success-json! (run-kimen repo-root base-env ["sync" "push" "--remote" "team" "--passphrase-cmd" pass-cmd "--json"]) "sync_push")]
          (ensure! (= "to_remote" (get to-remote "mode")) "expected to_remote mode for reset-baseline --to-remote" {:to-remote to-remote})
          (ensure! (= "rev" (get rev-reset "mode")) "expected rev mode for reset-baseline --rev" {:rev-reset rev-reset})
          (ensure! (= "sync_push" (get reset-push "action")) "expected sync push after reset-baseline --to-remote" {:reset-push reset-push}))

        (spit sync-bundle-path "tampered-remote-again\n")
        (expect-error-json! (run-kimen repo-root base-env ["sync" "push" "--remote" "team" "--json"]) 31 "remote_changed")
        (let [force-push (expect-success-json! (run-kimen repo-root base-env ["sync" "push" "--remote" "team" "--force" "--passphrase-cmd" pass-cmd "--json"]) "sync_push")]
          (ensure! (= true (get force-push "forced")) "expected forced=true for sync push --force" {:force-push force-push}))
        (expect-error-json! (run-kimen repo-root base-env ["sync" "reset-baseline" "--remote" "team" "--clear" "--json"]) 32 "sync_failed")
        (let [clear (expect-success-json! (run-kimen repo-root base-env ["sync" "reset-baseline" "--remote" "team" "--clear" "--yes" "--json"]) "sync_reset_baseline")]
          (ensure! (= "clear" (get clear "mode")) "expected clear mode for reset-baseline --clear" {:clear clear}))
        (expect-error-json! (run-kimen repo-root base-env ["sync" "push" "--remote" "team" "--json"]) 31 "no_local_baseline")
        (expect-success-json! (run-kimen repo-root base-env ["sync" "push" "--remote" "team" "--force" "--passphrase-cmd" pass-cmd "--json"]) "sync_push")

        (expect-success-json! (run-kimen repo-root base-env ["sync" "conflicts" "--remote" "team" "--json"]) "sync_conflicts")
        (expect-success-json! (run-kimen repo-root base-env ["secret" "set" "api_key" "--value" "local-change" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"]) "set")
        (expect-success-json! (run-kimen repo-root base-env ["vault" "init" "--vault" remote-vault "--passphrase-cmd" pass-cmd "--json"]) "vault_init")
        (expect-success-json! (run-kimen repo-root base-env ["secret" "set" "api_key" "--value" "remote-win" "--vault" remote-vault "--passphrase-cmd" pass-cmd "--json"]) "set")
        (expect-success-json! (run-kimen repo-root base-env ["bundle" "seal" "--vault" remote-vault "--out" sync-bundle-path "--recipient" recipient "--json"]) "bundle_seal")
        (expect-error-json! (run-kimen repo-root base-env ["sync" "pull" "--remote" "team" "--json"]) 31 "overlapping_changes")
        (let [sync-auto-blocked (expect-sync-report-error! (run-kimen repo-root base-env ["sync" "--remote" "team" "--passphrase-cmd" pass-cmd "--json"]) 31 "overlapping_changes")]
          (ensure! (= "manual_reconcile" (get sync-auto-blocked "recommended_action")) "expected manual_reconcile recommendation for blocked sync auto" {:sync-auto-blocked sync-auto-blocked}))
        (let [changes (expect-success-json! (run-kimen repo-root base-env ["sync" "changes" "--remote" "team" "--passphrase-cmd" pass-cmd "--json"]) "sync_changes")
              resolve (expect-success-json! (run-kimen repo-root base-env ["sync" "resolve" "--remote" "team" "--take" "remote" "--key" "api_key" "--passphrase-cmd" pass-cmd "--json"]) "sync_resolve")
              pull (expect-success-json! (run-kimen repo-root base-env ["sync" "pull" "--remote" "team" "--reconcile" "--passphrase-cmd" pass-cmd "--json"]) "sync_pull_reconcile")
              api-key (expect-success-json! (run-kimen repo-root base-env ["secret" "get" "api_key" "--unsafe-stdout" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"]) "get")
              sync-auto-noop (expect-success-json! (run-kimen repo-root base-env ["sync" "--remote" "team" "--json"]) "sync")]
          (ensure! (= true (get changes "has_baseline")) "expected has_baseline=true for sync changes" {:changes changes})
          (ensure! (= "remote" (get resolve "take")) "expected remote take for sync resolve" {:resolve resolve})
          (ensure! (= true (get pull "ok")) "expected successful sync pull after resolve" {:pull pull})
          (ensure! (= "cmVtb3RlLXdpbg==" (get api-key "value_b64")) "expected remote value after reconcile pull" {:api-key api-key})
          (ensure! (= "noop" (get sync-auto-noop "decision")) "expected sync auto noop after reconcile pull" {:sync-auto sync-auto-noop}))
        (expect-success-json! (run-kimen repo-root base-env ["secret" "set" "api_key" "--value" "auto-push" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"]) "set")
        (let [sync-auto-push (expect-success-json! (run-kimen repo-root base-env ["sync" "--remote" "team" "--passphrase-cmd" pass-cmd "--json"]) "sync")]
          (ensure! (= "push" (get sync-auto-push "decision")) "expected sync auto push decision" {:sync-auto-push sync-auto-push})))

      (expect-error-json! (run-kimen repo-root base-env ["sync" "push" "--remote" "team" "--reconcile" "--json"]) 32 "sync_failed")
      (expect-error-json! (run-kimen repo-root base-env ["sync" "pull" "--remote" "team" "--force" "--json"]) 32 "sync_failed")
      (.delete (io/file vault-path))
      (let [status (expect-success-json! (run-kimen repo-root base-env ["sync" "status" "--remote" "team" "--json"]) "sync_status")]
        (ensure! (= false (get status "has_local")) "expected has_local=false after local vault delete" {:status status})
        (ensure! (= "sync_pull" (get status "recommended_action")) "expected recommended_action=sync_pull after local vault delete" {:status status}))
      (expect-success-json! (run-kimen repo-root base-env ["sync" "pull" "--remote" "team" "--passphrase-cmd" pass-cmd "--json"]) "sync_pull")
      (let [restore-backup (str (.getPath temp-dir) "/manual-restore.backup.db")]
        (expect-success-json! (run-kimen repo-root base-env ["secret" "set" "api_key" "--value" "restore-source" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"]) "set")
        (spit restore-backup (slurp vault-path))
        (expect-success-json! (run-kimen repo-root base-env ["secret" "set" "api_key" "--value" "restore-mutated" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"]) "set")
        (expect-success-json! (run-kimen repo-root base-env ["sync" "restore" "--backup" restore-backup "--json"]) "sync_restore")
        (expect-success-json! (run-kimen repo-root base-env ["sync" "restore" "--backup" restore-backup "--no-backup" "--json"]) "sync_restore")
        (let [restored (expect-success-json! (run-kimen repo-root base-env ["secret" "get" "api_key" "--unsafe-stdout" "--vault" vault-path "--passphrase-cmd" pass-cmd "--json"]) "get")]
          (ensure! (= "cmVzdG9yZS1zb3VyY2U=" (get restored "value_b64")) "expected source value after sync restore" {:restored restored})))

      (if (git-available?)
        (let [git-repo (str (.getPath temp-dir) "/team.git")
              git-cfg-a (str (.getPath temp-dir) "/git-config-a.json")
              git-vault-a (str (.getPath temp-dir) "/git-vault-a.db")
              git-cfg-b (str (.getPath temp-dir) "/git-config-b.json")
              git-vault-b (str (.getPath temp-dir) "/git-vault-b.db")
              env-a {"KIMEN_CONFIG" git-cfg-a
                     "KIMEN_VAULT" git-vault-a}
              env-b {"KIMEN_CONFIG" git-cfg-b
                     "KIMEN_VAULT" git-vault-b}]
          (run-git! ["init" "--bare" git-repo])
          (expect-success-json! (run-kimen repo-root env-a ["vault" "init" "--vault" git-vault-a "--passphrase-cmd" pass-cmd "--json"]) "vault_init")
          (expect-success-json! (run-kimen repo-root env-a ["secret" "set" "api_key" "--value" "team-v1" "--vault" git-vault-a "--passphrase-cmd" pass-cmd "--json"]) "set")
          (expect-success-json! (run-kimen repo-root env-a ["remote" "add" "origin" "--type" "git" "--path" git-repo "--branch" "main" "--bundle-path" "vault.age" "--recipient" recipient "--identity" id-path "--json"]) "remote_add")
          (let [push-a (expect-success-json! (run-kimen repo-root env-a ["sync" "push" "--json"]) "sync_push")]
            (ensure! (= "git" (get push-a "remote_type")) "expected git remote type for sync push" {:push-a push-a}))

          (expect-success-json! (run-kimen repo-root env-b ["remote" "add" "origin" "--type" "git" "--path" git-repo "--branch" "main" "--bundle-path" "vault.age" "--recipient" recipient "--identity" id-path "--json"]) "remote_add")
          (let [pull-b (expect-success-json! (run-kimen repo-root env-b ["sync" "pull" "--json"]) "sync_pull")
                value-b (expect-success-json! (run-kimen repo-root env-b ["secret" "get" "api_key" "--unsafe-stdout" "--vault" git-vault-b "--passphrase-cmd" pass-cmd "--json"]) "get")]
            (ensure! (= "git" (get pull-b "remote_type")) "expected git remote type for sync pull" {:pull-b pull-b})
            (ensure! (= "dGVhbS12MQ==" (get value-b "value_b64")) "expected team-v1 after git sync pull" {:value-b value-b}))

          (let [status-before (expect-success-json! (run-kimen repo-root env-a ["sync" "status" "--json"]) "sync_status")
                remote-rev-before (get status-before "remote_rev")]
            (expect-success-json! (run-kimen repo-root env-a ["secret" "set" "api_key" "--value" "team-v2" "--vault" git-vault-a "--passphrase-cmd" pass-cmd "--json"]) "set")
            (let [push-dry-run (expect-success-json! (run-kimen repo-root env-a ["sync" "push" "--dry-run" "--json"]) "sync_push_dry_run")
                  status-after (expect-success-json! (run-kimen repo-root env-a ["sync" "status" "--json"]) "sync_status")
                  lock-flag-error (expect-error-json! (run-kimen repo-root env-a ["sync" "push" "--lock-wait" "1s" "--json"]) 32 "sync_failed")]
              (ensure! (= "git" (get push-dry-run "remote_type")) "expected git remote type on sync push dry-run" {:push-dry-run push-dry-run})
              (ensure! (= remote-rev-before (get status-after "remote_rev")) "expected remote rev unchanged after git push dry-run" {:status-before status-before :status-after status-after})
              (ensure! (= 32 (get lock-flag-error "exit_code")) "expected sync_failed payload for git lock-flag rejection" {:lock-flag-error lock-flag-error}))))
        (println "skipping git integration checks: git unavailable")))

    (expect-success-json! (run-kimen repo-root base-env ["doctor" "--map" map-path "--bundle-in" bundle-path "--identity" id-path "--json"]) "doctor")
    (expect-success-json! (run-kimen repo-root base-env ["init" "ci-pr-safety" "--out" workflow-pr "--json"]) "init_ci_pr_safety")
    (expect-success-json! (run-kimen repo-root base-env ["init" "ci-deploy" "--out" workflow-deploy "--json"]) "init_ci_deploy")
    (expect-success-json! (run-kimen repo-root base-env ["init" "ci-sync-gate" "--out" workflow-sync "--json"]) "init_ci_sync_gate")

    (println "cli integration tests passed")
    0))

(defn -main
  [& _]
  (try
    (System/exit (int (main!)))
    (catch Exception e
      (binding [*out* *err*]
        (println "cli integration tests failed:" (.getMessage e))
        (when-let [data (ex-data e)]
          (println (pr-str data))))
      (System/exit 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))

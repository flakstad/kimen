(ns kimen.cli-helpers-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [kimen.cli.parse :as parse]
   [kimen.cli.usage :as usage]
   [clojure.string :as str]))

(set! *warn-on-reflection* true)

(deftest parse-flag-value-cases
  (testing "flag value via separate arg"
    (let [[v rest-args err] (parse/parse-flag-value ["--vault" "/tmp/vault.db" "tail"] "--vault")]
      (is (= "/tmp/vault.db" v))
      (is (= ["tail"] (vec rest-args)))
      (is (nil? err))))
  (testing "flag value via inline assignment"
    (let [[v rest-args err] (parse/parse-flag-value ["--vault=/tmp/vault.db" "tail"] "--vault")]
      (is (= "/tmp/vault.db" v))
      (is (= ["tail"] (vec rest-args)))
      (is (nil? err))))
  (testing "missing required value reports error"
    (let [[v rest-args err] (parse/parse-flag-value ["--vault"] "--vault")]
      (is (nil? v))
      (is (= ["--vault"] (vec rest-args)))
      (is (= "missing value for --vault" err)))))

(deftest parse-duration-ms-cases
  (is (= [30000 nil] (parse/parse-duration-ms "30s")))
  (is (= [300000 nil] (parse/parse-duration-ms "5m")))
  (is (= [42 nil] (parse/parse-duration-ms "42")))
  (is (= [nil "missing duration value"] (parse/parse-duration-ms "")))
  (let [[v err] (parse/parse-duration-ms "x3")]
    (is (nil? v))
    (is (str/includes? err "invalid duration"))))

(deftest parse-json-only-opts-cases
  (is (= [{:json? true} nil] (parse/parse-json-only-opts ["--json"])))
  (is (= [{:json? false} "unknown flag --bad"] (parse/parse-json-only-opts ["--bad"])))
  (is (= [{:json? false} "unexpected argument \"extra\""] (parse/parse-json-only-opts ["extra"]))))

(deftest split-before-double-dash-cases
  (is (= [["sync" "push"] ["echo" "ok"]]
         (mapv vec (parse/split-before-double-dash ["sync" "push" "--" "echo" "ok"]))))
  (is (= [["sync" "push"] []]
         (mapv vec (parse/split-before-double-dash ["sync" "push"])))))

(deftest parse-plan-and-map-lint-opts-cases
  (let [[map-lint-opts map-lint-err]
        (parse/parse-map-lint-opts ["--json" "--mode" "run" "--map" "demo.kmap" "--strict"])
        [plan-opts plan-err]
        (parse/parse-plan-opts ["--json" "--profile" "prod" "--env" "A=secret.a" "--" "echo" "ok"])]
    (is (nil? map-lint-err))
    (is (= {:json? true
            :mode "run"
            :strict? true
            :map-path "demo.kmap"
            :profile nil}
           map-lint-opts))
    (is (nil? plan-err))
    (is (= true (:json? plan-opts)))
    (is (= "prod" (:profile plan-opts)))
    (is (= ["A=secret.a"] (:env-mappings plan-opts)))
    (is (= ["echo" "ok"] (:command plan-opts)))))

(deftest parse-vault-opts-cases
  (let [[auth-opts auth-err]
        (parse/parse-vault-auth-opts ["--json" "--vault" "/tmp/v.db" "--passphrase-cmd" "print pass" "tail"])
        [rekey-opts rekey-err]
        (parse/parse-vault-rekey-opts ["--json"
                                       "--vault=/tmp/v.db"
                                       "--old-passphrase-file" "old.pass"
                                       "--new-passphrase-file" "new.pass"
                                       "--dry-run"
                                       "--no-backup"])]
    (is (nil? auth-err))
    (is (= true (:json? auth-opts)))
    (is (= "/tmp/v.db" (:vault-path auth-opts)))
    (is (= "print pass" (:passphrase-cmd auth-opts)))
    (is (= ["tail"] (:rest auth-opts)))
    (is (nil? rekey-err))
    (is (= true (:dry-run? rekey-opts)))
    (is (= true (:no-backup? rekey-opts)))
    (is (= "old.pass" (:old-passphrase-file rekey-opts)))
    (is (= "new.pass" (:new-passphrase-file rekey-opts)))))

(deftest parse-secret-run-render-doctor-cases
  (let [[secret-opts secret-err]
        (parse/parse-secret-set-opts ["--json" "demo.secret" "--stdin" "--vault" "/tmp/v.db"])
        [run-opts run-err]
        (parse/parse-run-opts ["--json" "--profile" "prod" "--env" "A=secret.a" "--" "env"])
        [render-opts render-err]
        (parse/parse-render-opts ["--json" "--profile" "prod" "--dir" "/tmp/render" "--file" "a.txt=secret.a"])
        [doctor-opts doctor-err]
        (parse/parse-doctor-opts ["--json" "--strict" "--profile" "prod" "--allow-missing-vault"])]
    (is (nil? secret-err))
    (is (= true (:stdin? secret-opts)))
    (is (= "demo.secret" (:name secret-opts)))
    (is (= "/tmp/v.db" (:vault-path secret-opts)))
    (is (nil? run-err))
    (is (= "prod" (:profile run-opts)))
    (is (= ["A=secret.a"] (:env-mappings run-opts)))
    (is (= ["env"] (:command run-opts)))
    (is (nil? render-err))
    (is (= "prod" (:profile render-opts)))
    (is (= "/tmp/render" (:out-dir render-opts)))
    (is (= ["a.txt=secret.a"] (:file-mappings render-opts)))
    (is (nil? doctor-err))
    (is (= true (:strict? doctor-opts)))
    (is (= true (:allow-missing-vault? doctor-opts)))))

(deftest parse-bundle-and-remote-cases
  (let [[keygen-opts keygen-err]
        (parse/parse-bundle-keygen-opts ["--json" "--out" "id.agekey" "--overwrite" "--print-recipient"])
        [recipient-opts recipient-err]
        (parse/parse-bundle-recipient-opts ["--json" "--identity" "id.agekey"])
        [seal-opts seal-err]
        (parse/parse-bundle-seal-opts ["--json" "--vault" "vault.db" "--out" "vault.age" "--recipient" "age1xxx"])
        [open-opts open-err]
        (parse/parse-bundle-open-opts ["--json" "--in" "vault.age" "--out-vault" "vault.db" "--identity" "id.agekey" "--overwrite"])
        [remote-name-opts remote-name-err]
        (parse/parse-remote-name-opts ["--json" "team"])
        [remote-upsert-opts remote-upsert-err]
        (parse/parse-remote-upsert-opts ["--json" "team" "--type" "git" "--path" "git@example/repo.git" "--bundle-path" "vault.age"])]
    (is (nil? keygen-err))
    (is (= "id.agekey" (:out-path keygen-opts)))
    (is (= true (:overwrite? keygen-opts)))
    (is (nil? recipient-err))
    (is (= "id.agekey" (:identity-file recipient-opts)))
    (is (nil? seal-err))
    (is (= "vault.age" (:out-path seal-opts)))
    (is (= ["age1xxx"] (:recipients seal-opts)))
    (is (nil? open-err))
    (is (= "vault.age" (:in-path open-opts)))
    (is (= true (:overwrite? open-opts)))
    (is (nil? remote-name-err))
    (is (= "team" (:name remote-name-opts)))
    (is (nil? remote-upsert-err))
    (is (= "team" (:name remote-upsert-opts)))
    (is (= "git" (:type remote-upsert-opts)))
    (is (= "vault.age" (:bundle-path remote-upsert-opts)))))

(deftest parse-sync-cases
  (let [[init-opts init-err]
        (parse/parse-sync-init-opts ["--json" "team" "--type" "git" "--path" "git@example/repo.git"])
        [status-opts status-err]
        (parse/parse-sync-status-opts ["--json" "--stale-threshold" "30s" "--remote" "team"])
        [transfer-opts transfer-err]
        (parse/parse-sync-transfer-opts ["--json" "--dry-run" "--lock-wait" "2s" "--remote" "team"])
        [auto-opts auto-err]
        (parse/parse-sync-auto-opts ["--json" "--strict" "--profile" "prod" "--remote" "team"])
        [reset-opts reset-err]
        (parse/parse-sync-reset-baseline-opts ["--json" "--remote" "team" "--to-remote" "--yes"])
        [unlock-opts unlock-err]
        (parse/parse-sync-unlock-opts ["--json" "--if-older-than" "90s" "--yes" "--remote" "team"])]
    (is (nil? init-err))
    (is (= "team" (:remote init-opts)))
    (is (= "git" (:type init-opts)))
    (is (nil? status-err))
    (is (= 30000 (:stale-threshold-ms status-opts)))
    (is (nil? transfer-err))
    (is (= true (:dry-run? transfer-opts)))
    (is (= 2000 (:lock-wait-ms transfer-opts)))
    (is (nil? auto-err))
    (is (= "prod" (:profile auto-opts)))
    (is (= true (:strict? auto-opts)))
    (is (nil? reset-err))
    (is (= true (:to-remote? reset-opts)))
    (is (= true (:yes? reset-opts)))
    (is (nil? unlock-err))
    (is (= true (:yes? unlock-opts)))
    (is (= 90000 (:if-older-than-ms unlock-opts)))))

(deftest parse-init-and-config-cases
  (let [[init-pr-opts init-pr-err]
        (parse/parse-init-ci-pr-safety-opts ["--json" "--force" "--profile" "prod" "--command" "echo ok"])
        [init-deploy-opts init-deploy-err]
        (parse/parse-init-ci-deploy-opts ["--json" "--profile" "prod" "--deploy-command" "./deploy.sh"])
        [init-sync-opts init-sync-err]
        (parse/parse-init-ci-sync-gate-opts ["--json" "--remote-name" "team" "--stale-threshold" "30m"])
        [cfg-show-opts cfg-show-err]
        (parse/parse-config-show-opts ["--pretty=false"])
        [cfg-unlock-opts cfg-unlock-err]
        (parse/parse-config-unlock-set-opts ["--json" "exec" "--" "security" "find-generic-password"])
        [cfg-vault-opts cfg-vault-err]
        (parse/parse-config-vault-set-opts ["--json" "/tmp/vault.db"])]
    (is (nil? init-pr-err))
    (is (= true (:force? init-pr-opts)))
    (is (= "prod" (:profile init-pr-opts)))
    (is (nil? init-deploy-err))
    (is (= "./deploy.sh" (:deploy-command init-deploy-opts)))
    (is (nil? init-sync-err))
    (is (= "team" (:remote-name init-sync-opts)))
    (is (= "30m" (:stale-threshold init-sync-opts)))
    (is (nil? cfg-show-err))
    (is (= false (:pretty cfg-show-opts)))
    (is (nil? cfg-unlock-err))
    (is (= "exec" (:method cfg-unlock-opts)))
    (is (= ["security" "find-generic-password"] (:command cfg-unlock-opts)))
    (is (nil? cfg-vault-err))
    (is (= "/tmp/vault.db" (:vault-path cfg-vault-opts)))))

(deftest parse-json-map-and-cmd-string-cases
  (is (= {"ok" true}
         (parse/parse-json-map "{\"ok\":true}")))
  (is (nil? (parse/parse-json-map "not-json")))
  (is (= ["printf" "hello"]
         (parse/parse-cmd-string "  printf   hello  ")))
  (is (= []
         (parse/parse-cmd-string ""))))

(deftest usage-help-topics-include-known-commands
  (let [completion "completion help text"
        topics (usage/help-topics completion)]
    (is (= completion (get topics "completion")))
    (is (str/includes? (get topics "version") "kimen version"))
    (is (str/includes? usage/usage "Commands:"))
    (is (str/includes? usage/secret-usage "kimen secret set <name>"))))

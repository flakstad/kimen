(ns kimen.cli-helpers-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [kimen.cli.parse :as parse]
   [kimen.cli.usage :as usage]
   [clojure.string :as str]))

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

(deftest usage-help-topics-include-known-commands
  (let [completion "completion help text"
        topics (usage/help-topics completion)]
    (is (= completion (get topics "completion")))
    (is (str/includes? (get topics "version") "kimen version"))
    (is (str/includes? usage/usage "Commands:"))
    (is (str/includes? usage/secret-usage "kimen secret set <name>"))))

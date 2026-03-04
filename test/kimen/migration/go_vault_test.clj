(ns kimen.migration.go-vault-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [kimen.json :as json]
   [kimen.migration.go-vault :as migrate])
  (:import
   [java.nio.file Files]))

(defn- response
  [payload]
  {:exit 0
   :out (str (json/write-str payload) "\n")
   :err ""})

(defn- mock-exec
  [responses calls]
  (fn [{:keys [bin args]}]
    (swap! calls conj [bin args])
    (or (get responses [bin args])
        {:exit 1
         :out ""
         :err (str "unexpected command: " (pr-str [bin args]))})))

(defn- list-args
  [vault pass-cmd]
  ["secret" "list"
   "--vault" vault
   "--passphrase-cmd" pass-cmd
   "--json"])

(defn- get-args
  [name vault pass-cmd]
  ["secret" "get"
   name
   "--unsafe-stdout"
   "--vault" vault
   "--passphrase-cmd" pass-cmd
   "--json"])

(defn- init-args
  [vault pass-cmd]
  ["vault" "init"
   "--vault" vault
   "--passphrase-cmd" pass-cmd
   "--json"])

(defn- set-args
  [name value vault pass-cmd]
  ["secret" "set"
   name
   "--value" value
   "--vault" vault
   "--passphrase-cmd" pass-cmd
   "--json"])

(deftest parse-args-valid-and-missing
  (testing "valid parse"
    (let [{:keys [opts error]}
          (migrate/parse-args ["--source-bin" "/tmp/go-kimen"
                               "--source-vault" "/tmp/source.db"
                               "--source-passphrase-cmd" "printf old"
                               "--target-vault" "/tmp/target.db"
                               "--target-passphrase-cmd" "printf new"
                               "--init-target"
                               "--dry-run"
                               "--json"])]
      (is (nil? error))
      (is (= "/tmp/go-kimen" (:source-bin opts)))
      (is (= "/tmp/source.db" (:source-vault opts)))
      (is (= "/tmp/target.db" (:target-vault opts)))
      (is (true? (:init-target? opts)))
      (is (true? (:dry-run? opts)))
      (is (true? (:json? opts)))
      (is (= "bin/kimen" (:target-bin opts)))))

  (testing "optional leading -- separator is tolerated"
    (let [{:keys [opts error]}
          (migrate/parse-args ["--"
                               "--source-bin" "/tmp/go-kimen"
                               "--source-vault" "/tmp/source.db"
                               "--source-passphrase-cmd" "printf old"
                               "--target-vault" "/tmp/target.db"
                               "--target-passphrase-cmd" "printf new"])]
      (is (nil? error))
      (is (= "/tmp/go-kimen" (:source-bin opts)))))

  (testing "missing required args"
    (let [{:keys [error]} (migrate/parse-args ["--source-bin" "go-kimen"])]
      (is (string? error))
      (is (.contains error "--source-vault is required"))
      (is (.contains error "--target-passphrase-cmd is required")))))

(deftest migrate-dry-run-reads-but-does-not-write
  (let [opts {:source-bin "go-kimen"
              :source-vault "/tmp/source.db"
              :source-passphrase-cmd "printf old"
              :target-bin "bin/kimen"
              :target-vault "/tmp/target.db"
              :target-passphrase-cmd "printf new"
              :init-target? true
              :dry-run? true}
        calls (atom [])
        responses {["go-kimen" (list-args "/tmp/source.db" "printf old")]
                   (response {"action" "list"
                              "names" ["api_key" "token"]})
                   ["go-kimen" (get-args "api_key" "/tmp/source.db" "printf old")]
                   (response {"action" "get"
                              "value_b64" "c2VjcmV0"})
                   ["go-kimen" (get-args "token" "/tmp/source.db" "printf old")]
                   (response {"action" "get"
                              "value_b64" "dG9rZW4="})}
        payload (migrate/migrate! opts (mock-exec responses calls))]
    (is (= true (:ok payload)))
    (is (= 0 (:exit_code payload)))
    (is (= 2 (:count payload)))
    (is (= ["api_key" "token"] (:names payload)))
    (is (= true (:dry_run payload)))
    (is (= false (:target_initialized payload)))
    (is (= 3 (count @calls)))))

(deftest migrate-inits-target-and-writes-secrets
  (let [tmp-dir (.toFile (Files/createTempDirectory "kimen-migrate-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        target-vault (str (.getPath tmp-dir) "/target.db")
        opts {:source-bin "go-kimen"
              :source-vault "/tmp/source.db"
              :source-passphrase-cmd "printf old"
              :target-bin "bin/kimen"
              :target-vault target-vault
              :target-passphrase-cmd "printf new"
              :init-target? true
              :dry-run? false}
        calls (atom [])
        responses {["bin/kimen" (init-args target-vault "printf new")]
                   (response {"action" "vault_init"})
                   ["go-kimen" (list-args "/tmp/source.db" "printf old")]
                   (response {"action" "list"
                              "names" ["api_key" "token"]})
                   ["go-kimen" (get-args "api_key" "/tmp/source.db" "printf old")]
                   (response {"action" "get"
                              "value_b64" "c2VjcmV0"})
                   ["go-kimen" (get-args "token" "/tmp/source.db" "printf old")]
                   (response {"action" "get"
                              "value_b64" "dG9rZW4="})
                   ["bin/kimen" (set-args "api_key" "secret" target-vault "printf new")]
                   (response {"action" "set"})
                   ["bin/kimen" (set-args "token" "token" target-vault "printf new")]
                   (response {"action" "set"})}
        payload (migrate/migrate! opts (mock-exec responses calls))]
    (is (= true (:ok payload)))
    (is (= 0 (:exit_code payload)))
    (is (= 2 (:count payload)))
    (is (= false (:dry_run payload)))
    (is (= true (:target_initialized payload)))
    (is (= 6 (count @calls)))))

(deftest migrate-propagates-command-failures
  (let [opts {:source-bin "go-kimen"
              :source-vault "/tmp/source.db"
              :source-passphrase-cmd "printf old"
              :target-bin "bin/kimen"
              :target-vault "/tmp/target.db"
              :target-passphrase-cmd "printf new"
              :init-target? false
              :dry-run? false}
        calls (atom [])
        responses {["go-kimen" (list-args "/tmp/source.db" "printf old")]
                   {:exit 15 :out "" :err "wrong passphrase"}}]
    (try
      (migrate/migrate! opts (mock-exec responses calls))
      (is false "expected migrate! to throw")
      (catch Exception e
        (is (.contains (.getMessage e) "command failed"))
        (is (= 15 (:exit (ex-data e))))
        (is (= 1 (count @calls)))))))

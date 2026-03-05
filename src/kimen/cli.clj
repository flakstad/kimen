(ns kimen.cli
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as sh]
   [clojure.string :as str]
   [kimen.bundle :as bundle]
   [kimen.commands.completion :as completion]
   [kimen.commands.doctor :as doctor]
   [kimen.commands.init :as init]
   [kimen.commands.map-lint :as map-lint]
   [kimen.commands.plan :as plan]
   [kimen.commands.version :as version]
   [kimen.config :as config]
   [kimen.exit-code :as exit-code]
   [kimen.json :as json]
   [kimen.mapfile :as mapfile]
   [kimen.passphrase :as passphrase]
   [kimen.projection :as projection]
   [kimen.reason-codes :as reasons]
   [kimen.sync-state :as sync-state]
   [kimen.vault-path :as vault-path]
   [kimen.vault.v2 :as vault-v2])
  (:import
   [java.security MessageDigest]
   [java.nio.file Files StandardCopyOption]
   [java.nio.file Paths]
   [java.util Base64]))

(def usage
  (str/join
   "\n"
   ["kimen"
    "Secret management, projection, and sync."
    ""
    "Usage:"
    "  kimen <command> [<subcommand>] [options]"
    "  kimen help <command>"
    ""
    "Commands:"
    "  version   Build and version metadata"
    "  config    Show or edit local config"
    "  vault     Initialize, inspect, and rekey vault files"
    "  secret    Set, list, read, remove, or rename secrets"
    "  remote    Manage sync remote definitions"
    "  sync      Sync workflows (push/pull/status/conflicts/etc.)"
    "  bundle    age key and bundle operations"
    "  map       Mapping utilities (currently: lint)"
    "  plan      Show projection plan/diff"
    "  run       Run a command with projected env/files/stdin"
    "  render    Render projected files"
    "  envfile   Generate envfile output"
    "  doctor    Validate config/vault/map/bundle/remote health"
    "  init      Generate CI helper scripts"
    "  project   Aliases for run/render/plan"
    "  completion Generate shell completion scripts"
    ""
    "Use `kimen <command> --help` for subcommand usage."
    ""]))

(def config-usage
  (str/join
   "\n"
   ["kimen config"
    ""
    "Usage:"
    "  kimen config path [--json]"
    "  kimen config show [--pretty=true|false]"
    "  kimen config vault set <vault-path> [--json]"
    "  kimen config vault show [--json]"
    "  kimen config vault clear [--json]"
    "  kimen config unlock set <prompt|env|stdin|exec> [-- <command> [args...]] [--json]"
    "  kimen config unlock show [--json]"
    "  kimen config unlock clear [--json]"
    ""]))

(def vault-usage
  (str/join
   "\n"
   ["kimen vault"
    ""
    "Usage:"
    "  kimen vault init [--vault <path>] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json]"
    "  kimen vault info [--vault <path>] [--json]"
    "  kimen vault path [--vault <path>] [--json]"
    "  kimen vault rekey [--vault <path>] [--passphrase-stdin|--passphrase-cmd <cmd>|--old-passphrase-file <path>] [--new-passphrase-stdin|--new-passphrase-cmd <cmd>|--new-passphrase-file <path>|--new-passphrase-env <VAR>] [--dry-run] [--no-backup|--backup-dir <path>] [--json]"
    ""]))

(def secret-usage
  (str/join
   "\n"
   ["kimen secret"
    ""
    "Usage:"
    "  kimen secret set <name> [--stdin|--value <text>] [--vault <path>] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json]"
    "  kimen secret list [--vault <path>] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json]"
    "  kimen secret get <name> --unsafe-stdout [--vault <path>] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json]"
    "  kimen secret rm <name> [--vault <path>] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json]"
    "  kimen secret mv <old-name> <new-name> [--vault <path>] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json]"
    ""]))

(def secret-set-usage-line
  "Usage: kimen secret set <name> [--stdin|--value <text>] [--vault <path>] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json]")

(def secret-get-usage-line
  "Usage: kimen secret get <name> --unsafe-stdout [--vault <path>] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json]")

(def secret-rm-usage-line
  "Usage: kimen secret rm <name> [--vault <path>] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json]")

(def secret-mv-usage-line
  "Usage: kimen secret mv <old-name> <new-name> [--vault <path>] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json]")

(def remote-usage
  (str/join
   "\n"
   ["kimen remote"
    ""
    "Usage:"
    "  kimen remote add <name> --path <path> [--type fs|git] [--recipient <age1...>] [--identity <path>] [--branch <name>] [--bundle-path <path>] [--derive-recipient|--no-derive-recipient] [--json]"
    "  kimen remote get <name> [--json]"
    "  kimen remote set <name> [--type fs|git] [--path <path>] [--recipient <age1...>] [--identity <path>] [--branch <name>] [--bundle-path <path>] [--derive-recipient|--no-derive-recipient] [--json]"
    "  kimen remote list [--json]"
    "  kimen remote rm <name> [--json]"
    ""]))

(def sync-usage
  (str/join
   "\n"
   ["kimen sync"
    ""
    "Usage:"
    "  kimen sync [--remote <name>] [--dry-run] [--check] [--no-doctor] [--strict] [--terse] [--stale-threshold <dur>] [--profile <name>] [--bundle-in <path>] [--identity <path>] [--allow-missing-vault] [--force] [--reconcile] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json]"
    "  kimen sync init [name] [--remote <name>] [--type fs|git] [--path <path>] [--recipient <age1...>] [--identity <path>] [--branch <name>] [--bundle-path <path>] [--update] [--no-check] [--json]"
    "  kimen sync preflight [--remote <name>] [--profile <name>] [--bundle-in <path>] [--identity <path>] [--stale-threshold <dur>] [--strict] [--allow-missing-vault] [--only <check>] [--skip <check>] [--json]"
    "  kimen sync status [--remote <name>] [--stale-threshold <dur>] [--strict] [--terse] [--json]"
    "  kimen sync conflicts [--remote <name>] [--stale-threshold <dur>] [--strict] [--terse] [--json]"
    "  kimen sync changes [--remote <name>] [--terse] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json]"
    "  kimen sync push [--remote <name>] [--dry-run] [--force] [--lock-wait <dur>] [--break-stale-lock-after <dur>] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json]"
    "  kimen sync pull [--remote <name>] [--dry-run] [--reconcile] [--no-backup] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json]"
    "  kimen sync resolve [--remote <name>] --take local|remote [--key <name>] [--key <name> ...] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json]"
    "  kimen sync reset-baseline [--remote <name>] (--clear|--to-remote|--rev <sha256>) --yes [--passphrase-stdin|--passphrase-cmd <cmd>] [--json]"
    "  kimen sync unlock [--remote <name>] [--if-older-than <dur>] --yes [--json]"
    "  kimen sync restore --backup <path> [--no-backup] [--json]"
    ""]))

(def bundle-usage
  (str/join
   "\n"
   ["kimen bundle"
    ""
    "Usage:"
    "  kimen bundle keygen --out <path> [--overwrite] [--print-recipient] [--json]"
    "  kimen bundle recipient (--identity <path>|--identity-stdin) [--json]"
    "  kimen bundle seal [--vault <path>] --out <path> --recipient <age1...> [--recipient <age1...> ...] [--json]"
    "  kimen bundle open --in <path> [--out-vault <path>] (--identity <path>|--identity-stdin) [--overwrite] [--json]"
    ""]))

(def map-usage
  (str/join
   "\n"
   ["kimen map"
    ""
    "Usage:"
    "  kimen map lint [--map <path>|--profile <name>] [--mode all|run|render|envfile] [--strict] [--json]"
    ""]))

(def init-usage
  (str/join
   "\n"
   ["kimen init"
    ""
    "Usage:"
    "  kimen init ci-pr-safety [--out <path>] [--force] [--profile <name>] [--command <cmd>] [--json]"
    "  kimen init ci-deploy [--out <path>] [--force] [--profile <name>] [--deploy-command <cmd>] [--json]"
    "  kimen init ci-sync-gate [--out <path>] [--force] [--remote-name <name>] [--remote-type git|fs] [--remote-path <path>] [--remote-branch <name>] [--remote-bundle-path <path>] [--local-bundle <path>] [--profile <name>] [--stale-threshold <dur>] [--json]"
    ""]))

(def run-usage
  (str/join
   "\n"
   ["kimen run"
    ""
    "Usage:"
    "  kimen run [--map <path>|--profile <name>] [--env VAR=<value>] [--file relpath=<value>] [--envpath VAR=relpath] [--stdin <value>] [--files-dir <path>] [--json] [--dry-run] [-- <command> [args...]]"
    ""]))

(def render-usage
  (str/join
   "\n"
   ["kimen render"
    ""
    "Usage:"
    "  kimen render [--map <path>|--profile <name>] [--file relpath=<value>] (--dir <path>|--systemd-service <name>) [--runtime-dir <path>] [--print-systemd-hints] [--json]"
    ""]))

(def envfile-usage
  (str/join
   "\n"
   ["kimen envfile"
    ""
    "Usage:"
    "  kimen envfile [--map <path>|--profile <name>] [--env VAR=<value>] [--file relpath=<value>] [--envpath VAR=relpath] --out <path> [--files-dir <path>] [--json]"
    ""]))

(def plan-usage
  (str/join
   "\n"
   ["kimen plan"
    ""
    "Usage:"
    "  kimen plan [--map <path>|--profile <name>] [--env VAR=<value>] [--file relpath=<value>] [--envpath VAR=relpath] [--stdin <value>] [--against-map <path>|--against-profile <name>] [--mode run|render|envfile] [--json] [-- <command> [args...]]"
    ""]))

(def doctor-usage
  (str/join
   "\n"
   ["kimen doctor"
    ""
    "Usage:"
    "  kimen doctor [--map <path>|--profile <name>] [--bundle-in <path>] [--identity <path>] [--strict] [--allow-missing-vault] [--json]"
    ""]))

(def project-usage
  (str/join
   "\n"
  ["kimen project"
    ""
    "Usage:"
    "  kimen project run [--map <path>|--profile <name>] [--env VAR=<value>] [--file relpath=<value>] [--envpath VAR=relpath] [--stdin <value>] [--files-dir <path>] [--json] [--dry-run] [-- <command> [args...]]"
    "  kimen project render [--map <path>|--profile <name>] [--file relpath=<value>] (--dir <path>|--systemd-service <name>) [--runtime-dir <path>] [--print-systemd-hints] [--json]"
    "  kimen project plan [--map <path>|--profile <name>] [--env VAR=<value>] [--file relpath=<value>] [--envpath VAR=relpath] [--stdin <value>] [--against-map <path>|--against-profile <name>] [--mode run|render|envfile] [--json] [-- <command> [args...]]"
    ""]))

(def ^:private help-topics
  {"version" (str/join "\n" ["kimen version" "" "Usage:" "  kimen version [--json]" ""])
   "config" config-usage
   "vault" vault-usage
   "secret" secret-usage
   "remote" remote-usage
   "sync" sync-usage
   "bundle" bundle-usage
   "map" map-usage
   "plan" plan-usage
   "run" run-usage
   "render" render-usage
   "envfile" envfile-usage
   "doctor" doctor-usage
   "init" init-usage
   "project" project-usage
   "completion" completion/usage})

(def remote-name-re #"^[A-Za-z0-9_.-]+$")
(def systemd-service-name-re #"^[A-Za-z0-9_.@-]+$")
(def env-remote-name "KIMEN_REMOTE")

(defn- getenv
  [ctx k]
  (if-let [lookup (:getenv ctx)]
    (lookup k)
    (System/getenv k)))

(defn- json-line
  [x]
  (str (json/write-str x) "\n"))

(defn- result
  [{:keys [exit-code stdout stderr]}]
  {:exit-code (int (or exit-code 0))
   :stdout stdout
   :stderr stderr})

(defn- success-json
  [payload]
  (result {:exit-code 0
           :stdout (json-line payload)}))

(defn- error-envelope
  [exit-code reason message]
  {:ok false
   :error message
   :exit_code exit-code
   :reason reason})

(defn- error-json
  [exit-code reason message]
  (result {:exit-code exit-code
           :stderr (json-line (error-envelope exit-code reason message))}))

(defn- error-text
  [exit-code message]
  (result {:exit-code exit-code
           :stderr (str message "\n")}))

(def ^:private help-args #{"help" "--help" "-h"})

(defn- help-arg?
  [s]
  (contains? help-args (str s)))

(defn- help-result
  [text]
  (result {:exit-code 0
           :stdout text}))

(defn- error-with-help
  [exit-code message help-text]
  (result {:exit-code exit-code
           :stderr (str message "\n\n" help-text)}))

(declare help-topics)

(defn- command-help
  [topic]
  (or (get help-topics (some-> topic str/trim))
      usage))

(defn- parse-flag-value
  [args flag]
  (let [prefix (str flag "=")]
    (cond
      (empty? args) [nil args nil]
      (= flag (first args))
      (if-let [v (second args)]
        [v (nnext args) nil]
        [nil args (str "missing value for " flag)])
      (str/starts-with? (first args) prefix)
      [(subs (first args) (count prefix)) (next args) nil]
      :else
      [nil args nil])))

(def ^:private duration-unit-ms
  {"ms" 1
   "s" 1000
   "m" (* 60 1000)
   "h" (* 60 60 1000)
   "d" (* 24 60 60 1000)})

(defn- parse-duration-ms
  [raw]
  (let [s (some-> raw str/trim str/lower-case)]
    (cond
      (str/blank? s)
      [nil "missing duration value"]

      (re-matches #"^-?\d+$" s)
      [(Long/parseLong s) nil]

      :else
      (if-let [[_ n unit] (re-matches #"^(-?\d+)(ms|s|m|h|d)$" s)]
        [(* (Long/parseLong n)
            (long (get duration-unit-ms unit 0)))
         nil]
        [nil (format "invalid duration %s (expected e.g. 30s, 5m, 1h)" (pr-str raw))]))))

(defn- parse-json-only-opts
  [args]
  (loop [args args
         opts {:json? false}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- split-before-double-dash
  [args]
  (loop [left []
         args args]
    (if (empty? args)
      [left []]
      (if (= "--" (first args))
        [left (rest args)]
        (recur (conj left (first args)) (rest args))))))

(declare resolve-map-path!)
(declare resolve-mappings!)
(declare resolve-against-mappings!)
(declare run)
(declare run-sync-preflight-check)

(defn- parse-map-lint-opts
  [args]
  (loop [args args
         opts {:json? false
               :mode "all"
               :strict? false
               :map-path nil
               :profile nil}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (or (= a "--mode") (str/starts-with? a "--mode="))
          (let [[v next-args err] (parse-flag-value args "--mode")]
            (if err
              [opts err]
              (recur next-args (assoc opts :mode v))))

          (or (= a "--map") (str/starts-with? a "--map="))
          (let [[v next-args err] (parse-flag-value args "--map")]
            (if err
              [opts err]
              (recur next-args (assoc opts :map-path v))))

          (or (= a "--profile") (str/starts-with? a "--profile="))
          (let [[v next-args err] (parse-flag-value args "--profile")]
            (if err
              [opts err]
              (recur next-args (assoc opts :profile v))))

          (= a "--strict")
          (recur (rest args) (assoc opts :strict? true))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- parse-plan-opts
  [args]
  (let [[opt-args command] (split-before-double-dash args)]
    (loop [args opt-args
           opts {:json? false
                 :mode "run"
                 :map-path nil
                 :profile nil
                 :against-map nil
                 :against-profile nil
                 :env-mappings []
                 :file-mappings []
                 :envpath-mappings []
                 :stdin-spec nil
                 :command (vec command)}]
      (if (empty? args)
        [opts nil]
        (let [a (first args)]
          (cond
            (= a "--json")
            (recur (rest args) (assoc opts :json? true))

            (or (= a "--mode") (str/starts-with? a "--mode="))
            (let [[v next-args err] (parse-flag-value args "--mode")]
              (if err
                [opts err]
                (recur next-args (assoc opts :mode v))))

            (or (= a "--map") (str/starts-with? a "--map="))
            (let [[v next-args err] (parse-flag-value args "--map")]
              (if err
                [opts err]
                (recur next-args (assoc opts :map-path v))))

            (or (= a "--profile") (str/starts-with? a "--profile="))
            (let [[v next-args err] (parse-flag-value args "--profile")]
              (if err
                [opts err]
                (recur next-args (assoc opts :profile v))))

            (or (= a "--against-map") (str/starts-with? a "--against-map="))
            (let [[v next-args err] (parse-flag-value args "--against-map")]
              (if err
                [opts err]
                (recur next-args (assoc opts :against-map v))))

            (or (= a "--against-profile") (str/starts-with? a "--against-profile="))
            (let [[v next-args err] (parse-flag-value args "--against-profile")]
              (if err
                [opts err]
                (recur next-args (assoc opts :against-profile v))))

            (or (= a "--env") (str/starts-with? a "--env="))
            (let [[v next-args err] (parse-flag-value args "--env")]
              (if err
                [opts err]
                (recur next-args (update opts :env-mappings conj v))))

            (or (= a "--file") (str/starts-with? a "--file="))
            (let [[v next-args err] (parse-flag-value args "--file")]
              (if err
                [opts err]
                (recur next-args (update opts :file-mappings conj v))))

            (or (= a "--envpath") (str/starts-with? a "--envpath="))
            (let [[v next-args err] (parse-flag-value args "--envpath")]
              (if err
                [opts err]
                (recur next-args (update opts :envpath-mappings conj v))))

            (or (= a "--stdin") (str/starts-with? a "--stdin="))
            (let [[v next-args err] (parse-flag-value args "--stdin")]
              (if err
                [opts err]
                (recur next-args (assoc opts :stdin-spec v))))

            (str/starts-with? a "-")
            [opts (str "unknown flag " a)]

            :else
            [opts (str "unexpected argument " (pr-str a))]))))))

(defn- parse-vault-auth-opts
  [args]
  (loop [args args
         opts {:json? false
               :vault-path nil
               :passphrase-cmd nil
               :passphrase-stdin? false
               :rest []}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (= a "--passphrase-stdin")
          (recur (rest args) (assoc opts :passphrase-stdin? true))

          (or (= a "--vault") (str/starts-with? a "--vault="))
          (let [[v next-args err] (parse-flag-value args "--vault")]
            (if err
              [opts err]
              (recur next-args (assoc opts :vault-path v))))

          (or (= a "--passphrase-cmd") (str/starts-with? a "--passphrase-cmd="))
          (let [[v next-args err] (parse-flag-value args "--passphrase-cmd")]
            (if err
              [opts err]
              (recur next-args (assoc opts :passphrase-cmd v))))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          :else
          (recur (rest args) (update opts :rest conj a)))))))

(defn- parse-vault-rekey-opts
  [args]
  (loop [args args
         opts {:json? false
               :vault-path nil
               :passphrase-cmd nil
               :passphrase-stdin? false
               :old-passphrase-file nil
               :new-passphrase-cmd nil
               :new-passphrase-stdin? false
               :new-passphrase-file nil
               :new-passphrase-env nil
               :dry-run? false
               :no-backup? false
               :backup-dir nil
               :rest []}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (= a "--passphrase-stdin")
          (recur (rest args) (assoc opts :passphrase-stdin? true))

          (= a "--new-passphrase-stdin")
          (recur (rest args) (assoc opts :new-passphrase-stdin? true))

          (= a "--dry-run")
          (recur (rest args) (assoc opts :dry-run? true))

          (= a "--no-backup")
          (recur (rest args) (assoc opts :no-backup? true))

          (or (= a "--vault") (str/starts-with? a "--vault="))
          (let [[v next-args err] (parse-flag-value args "--vault")]
            (if err
              [opts err]
              (recur next-args (assoc opts :vault-path v))))

          (or (= a "--passphrase-cmd") (str/starts-with? a "--passphrase-cmd="))
          (let [[v next-args err] (parse-flag-value args "--passphrase-cmd")]
            (if err
              [opts err]
              (recur next-args (assoc opts :passphrase-cmd v))))

          (or (= a "--old-passphrase-file") (str/starts-with? a "--old-passphrase-file="))
          (let [[v next-args err] (parse-flag-value args "--old-passphrase-file")]
            (if err
              [opts err]
              (recur next-args (assoc opts :old-passphrase-file v))))

          (or (= a "--new-passphrase-cmd") (str/starts-with? a "--new-passphrase-cmd="))
          (let [[v next-args err] (parse-flag-value args "--new-passphrase-cmd")]
            (if err
              [opts err]
              (recur next-args (assoc opts :new-passphrase-cmd v))))

          (or (= a "--new-passphrase-file") (str/starts-with? a "--new-passphrase-file="))
          (let [[v next-args err] (parse-flag-value args "--new-passphrase-file")]
            (if err
              [opts err]
              (recur next-args (assoc opts :new-passphrase-file v))))

          (or (= a "--new-passphrase-env") (str/starts-with? a "--new-passphrase-env="))
          (let [[v next-args err] (parse-flag-value args "--new-passphrase-env")]
            (if err
              [opts err]
              (recur next-args (assoc opts :new-passphrase-env v))))

          (or (= a "--backup-dir") (str/starts-with? a "--backup-dir="))
          (let [[v next-args err] (parse-flag-value args "--backup-dir")]
            (if err
              [opts err]
              (recur next-args (assoc opts :backup-dir v))))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          :else
          (recur (rest args) (update opts :rest conj a)))))))

(defn- parse-secret-set-opts
  [args]
  (loop [args args
         opts {:json? false
               :vault-path nil
               :passphrase-cmd nil
               :passphrase-stdin? false
               :name nil
               :stdin? false
               :value nil
               :type nil}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (= a "--stdin")
          (recur (rest args) (assoc opts :stdin? true))

          (= a "--passphrase-stdin")
          (recur (rest args) (assoc opts :passphrase-stdin? true))

          (or (= a "--vault") (str/starts-with? a "--vault="))
          (let [[v next-args err] (parse-flag-value args "--vault")]
            (if err
              [opts err]
              (recur next-args (assoc opts :vault-path v))))

          (or (= a "--passphrase-cmd") (str/starts-with? a "--passphrase-cmd="))
          (let [[v next-args err] (parse-flag-value args "--passphrase-cmd")]
            (if err
              [opts err]
              (recur next-args (assoc opts :passphrase-cmd v))))

          (or (= a "--value") (str/starts-with? a "--value="))
          (let [[v next-args err] (parse-flag-value args "--value")]
            (if err
              [opts err]
              (recur next-args (assoc opts :value v))))

          (or (= a "--type") (str/starts-with? a "--type="))
          (let [[v next-args err] (parse-flag-value args "--type")]
            (if err
              [opts err]
              (recur next-args (assoc opts :type v))))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          (nil? (:name opts))
          (recur (rest args) (assoc opts :name a))

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- parse-secret-common-opts
  [args]
  (loop [args args
         opts {:json? false
               :vault-path nil
               :passphrase-cmd nil
               :passphrase-stdin? false
               :unsafe-stdout? false
               :rest []}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (= a "--passphrase-stdin")
          (recur (rest args) (assoc opts :passphrase-stdin? true))

          (= a "--unsafe-stdout")
          (recur (rest args) (assoc opts :unsafe-stdout? true))

          (or (= a "--vault") (str/starts-with? a "--vault="))
          (let [[v next-args err] (parse-flag-value args "--vault")]
            (if err
              [opts err]
              (recur next-args (assoc opts :vault-path v))))

          (or (= a "--passphrase-cmd") (str/starts-with? a "--passphrase-cmd="))
          (let [[v next-args err] (parse-flag-value args "--passphrase-cmd")]
            (if err
              [opts err]
              (recur next-args (assoc opts :passphrase-cmd v))))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          :else
          (recur (rest args) (update opts :rest conj a)))))))

(defn- parse-run-opts
  [args]
  (let [[opt-args command] (split-before-double-dash args)]
    (loop [args opt-args
           opts {:json? false
                 :dry-run? false
                 :map-path nil
                 :profile nil
                 :env-mappings []
                 :file-mappings []
                 :envpath-mappings []
                 :stdin-spec nil
                 :files-dir nil
                 :vault-path nil
                 :passphrase-cmd nil
                 :passphrase-stdin? false
                 :command (vec command)}]
      (if (empty? args)
        [opts nil]
        (let [a (first args)]
          (cond
            (= a "--json")
            (recur (rest args) (assoc opts :json? true))

            (= a "--dry-run")
            (recur (rest args) (assoc opts :dry-run? true))

            (= a "--passphrase-stdin")
            (recur (rest args) (assoc opts :passphrase-stdin? true))

            (or (= a "--map") (str/starts-with? a "--map="))
            (let [[v next-args err] (parse-flag-value args "--map")]
              (if err
                [opts err]
                (recur next-args (assoc opts :map-path v))))

            (or (= a "--profile") (str/starts-with? a "--profile="))
            (let [[v next-args err] (parse-flag-value args "--profile")]
              (if err
                [opts err]
                (recur next-args (assoc opts :profile v))))

            (or (= a "--env") (str/starts-with? a "--env="))
            (let [[v next-args err] (parse-flag-value args "--env")]
              (if err
                [opts err]
                (recur next-args (update opts :env-mappings conj v))))

            (or (= a "--file") (str/starts-with? a "--file="))
            (let [[v next-args err] (parse-flag-value args "--file")]
              (if err
                [opts err]
                (recur next-args (update opts :file-mappings conj v))))

            (or (= a "--envpath") (str/starts-with? a "--envpath="))
            (let [[v next-args err] (parse-flag-value args "--envpath")]
              (if err
                [opts err]
                (recur next-args (update opts :envpath-mappings conj v))))

            (or (= a "--stdin") (str/starts-with? a "--stdin="))
            (let [[v next-args err] (parse-flag-value args "--stdin")]
              (if err
                [opts err]
                (recur next-args (assoc opts :stdin-spec v))))

            (or (= a "--files-dir") (str/starts-with? a "--files-dir="))
            (let [[v next-args err] (parse-flag-value args "--files-dir")]
              (if err
                [opts err]
                (recur next-args (assoc opts :files-dir v))))

            (or (= a "--vault") (str/starts-with? a "--vault="))
            (let [[v next-args err] (parse-flag-value args "--vault")]
              (if err
                [opts err]
                (recur next-args (assoc opts :vault-path v))))

            (or (= a "--passphrase-cmd") (str/starts-with? a "--passphrase-cmd="))
            (let [[v next-args err] (parse-flag-value args "--passphrase-cmd")]
              (if err
                [opts err]
                (recur next-args (assoc opts :passphrase-cmd v))))

            (str/starts-with? a "-")
            [opts (str "unknown flag " a)]

            :else
            [opts (str "unexpected argument " (pr-str a))]))))))

(defn- parse-render-opts
  [args]
  (loop [args args
         opts {:json? false
               :map-path nil
               :profile nil
               :file-mappings []
               :out-dir nil
               :systemd-service nil
               :runtime-dir "/run"
               :print-systemd-hints? false
               :vault-path nil
               :passphrase-cmd nil
               :passphrase-stdin? false}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (= a "--passphrase-stdin")
          (recur (rest args) (assoc opts :passphrase-stdin? true))

          (or (= a "--map") (str/starts-with? a "--map="))
          (let [[v next-args err] (parse-flag-value args "--map")]
            (if err
              [opts err]
              (recur next-args (assoc opts :map-path v))))

          (or (= a "--profile") (str/starts-with? a "--profile="))
          (let [[v next-args err] (parse-flag-value args "--profile")]
            (if err
              [opts err]
              (recur next-args (assoc opts :profile v))))

          (or (= a "--file") (str/starts-with? a "--file="))
          (let [[v next-args err] (parse-flag-value args "--file")]
            (if err
              [opts err]
              (recur next-args (update opts :file-mappings conj v))))

          (or (= a "--dir") (str/starts-with? a "--dir="))
          (let [[v next-args err] (parse-flag-value args "--dir")]
            (if err
              [opts err]
              (recur next-args (assoc opts :out-dir v))))

          (or (= a "--systemd-service") (str/starts-with? a "--systemd-service="))
          (let [[v next-args err] (parse-flag-value args "--systemd-service")]
            (if err
              [opts err]
              (recur next-args (assoc opts :systemd-service v))))

          (or (= a "--runtime-dir") (str/starts-with? a "--runtime-dir="))
          (let [[v next-args err] (parse-flag-value args "--runtime-dir")]
            (if err
              [opts err]
              (recur next-args (assoc opts :runtime-dir v))))

          (= a "--print-systemd-hints")
          (recur (rest args) (assoc opts :print-systemd-hints? true))

          (or (= a "--vault") (str/starts-with? a "--vault="))
          (let [[v next-args err] (parse-flag-value args "--vault")]
            (if err
              [opts err]
              (recur next-args (assoc opts :vault-path v))))

          (or (= a "--passphrase-cmd") (str/starts-with? a "--passphrase-cmd="))
          (let [[v next-args err] (parse-flag-value args "--passphrase-cmd")]
            (if err
              [opts err]
              (recur next-args (assoc opts :passphrase-cmd v))))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- parse-envfile-opts
  [args]
  (loop [args args
         opts {:json? false
               :map-path nil
               :profile nil
               :env-mappings []
               :file-mappings []
               :envpath-mappings []
               :out-path nil
               :files-dir nil
               :vault-path nil
               :passphrase-cmd nil
               :passphrase-stdin? false}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (= a "--passphrase-stdin")
          (recur (rest args) (assoc opts :passphrase-stdin? true))

          (or (= a "--map") (str/starts-with? a "--map="))
          (let [[v next-args err] (parse-flag-value args "--map")]
            (if err
              [opts err]
              (recur next-args (assoc opts :map-path v))))

          (or (= a "--profile") (str/starts-with? a "--profile="))
          (let [[v next-args err] (parse-flag-value args "--profile")]
            (if err
              [opts err]
              (recur next-args (assoc opts :profile v))))

          (or (= a "--env") (str/starts-with? a "--env="))
          (let [[v next-args err] (parse-flag-value args "--env")]
            (if err
              [opts err]
              (recur next-args (update opts :env-mappings conj v))))

          (or (= a "--file") (str/starts-with? a "--file="))
          (let [[v next-args err] (parse-flag-value args "--file")]
            (if err
              [opts err]
              (recur next-args (update opts :file-mappings conj v))))

          (or (= a "--envpath") (str/starts-with? a "--envpath="))
          (let [[v next-args err] (parse-flag-value args "--envpath")]
            (if err
              [opts err]
              (recur next-args (update opts :envpath-mappings conj v))))

          (or (= a "--out") (str/starts-with? a "--out="))
          (let [[v next-args err] (parse-flag-value args "--out")]
            (if err
              [opts err]
              (recur next-args (assoc opts :out-path v))))

          (or (= a "--files-dir") (str/starts-with? a "--files-dir="))
          (let [[v next-args err] (parse-flag-value args "--files-dir")]
            (if err
              [opts err]
              (recur next-args (assoc opts :files-dir v))))

          (or (= a "--vault") (str/starts-with? a "--vault="))
          (let [[v next-args err] (parse-flag-value args "--vault")]
            (if err
              [opts err]
              (recur next-args (assoc opts :vault-path v))))

          (or (= a "--passphrase-cmd") (str/starts-with? a "--passphrase-cmd="))
          (let [[v next-args err] (parse-flag-value args "--passphrase-cmd")]
            (if err
              [opts err]
              (recur next-args (assoc opts :passphrase-cmd v))))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- parse-doctor-opts
  [args]
  (loop [args args
         opts {:json? false
               :strict? false
               :allow-missing-vault? false
               :map-path nil
               :profile nil
               :bundle-in nil
               :identity nil}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (= a "--strict")
          (recur (rest args) (assoc opts :strict? true))

          (= a "--allow-missing-vault")
          (recur (rest args) (assoc opts :allow-missing-vault? true))

          (or (= a "--map") (str/starts-with? a "--map="))
          (let [[v next-args err] (parse-flag-value args "--map")]
            (if err
              [opts err]
              (recur next-args (assoc opts :map-path v))))

          (or (= a "--profile") (str/starts-with? a "--profile="))
          (let [[v next-args err] (parse-flag-value args "--profile")]
            (if err
              [opts err]
              (recur next-args (assoc opts :profile v))))

          (or (= a "--bundle-in") (str/starts-with? a "--bundle-in="))
          (let [[v next-args err] (parse-flag-value args "--bundle-in")]
            (if err
              [opts err]
              (recur next-args (assoc opts :bundle-in v))))

          (or (= a "--identity") (str/starts-with? a "--identity="))
          (let [[v next-args err] (parse-flag-value args "--identity")]
            (if err
              [opts err]
              (recur next-args (assoc opts :identity v))))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- parse-bundle-keygen-opts
  [args]
  (loop [args args
         opts {:json? false
               :overwrite? false
               :print-recipient? false
               :out-path nil}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (= a "--overwrite")
          (recur (rest args) (assoc opts :overwrite? true))

          (= a "--print-recipient")
          (recur (rest args) (assoc opts :print-recipient? true))

          (or (= a "--out") (str/starts-with? a "--out="))
          (let [[v next-args err] (parse-flag-value args "--out")]
            (if err
              [opts err]
              (recur next-args (assoc opts :out-path v))))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- parse-bundle-recipient-opts
  [args]
  (loop [args args
         opts {:json? false
               :identity-file nil
               :identity-stdin? false}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (= a "--identity-stdin")
          (recur (rest args) (assoc opts :identity-stdin? true))

          (or (= a "--identity") (str/starts-with? a "--identity="))
          (let [[v next-args err] (parse-flag-value args "--identity")]
            (if err
              [opts err]
              (recur next-args (assoc opts :identity-file v))))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- parse-bundle-seal-opts
  [args]
  (loop [args args
         opts {:json? false
               :vault-path nil
               :out-path nil
               :recipients []}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (or (= a "--vault") (str/starts-with? a "--vault="))
          (let [[v next-args err] (parse-flag-value args "--vault")]
            (if err
              [opts err]
              (recur next-args (assoc opts :vault-path v))))

          (or (= a "--out") (str/starts-with? a "--out="))
          (let [[v next-args err] (parse-flag-value args "--out")]
            (if err
              [opts err]
              (recur next-args (assoc opts :out-path v))))

          (or (= a "--recipient") (str/starts-with? a "--recipient="))
          (let [[v next-args err] (parse-flag-value args "--recipient")]
            (if err
              [opts err]
              (recur next-args (update opts :recipients conj v))))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- parse-bundle-open-opts
  [args]
  (loop [args args
         opts {:json? false
               :overwrite? false
               :in-path nil
               :out-vault nil
               :identity-file nil
               :identity-stdin? false}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (= a "--overwrite")
          (recur (rest args) (assoc opts :overwrite? true))

          (= a "--identity-stdin")
          (recur (rest args) (assoc opts :identity-stdin? true))

          (or (= a "--in") (str/starts-with? a "--in="))
          (let [[v next-args err] (parse-flag-value args "--in")]
            (if err
              [opts err]
              (recur next-args (assoc opts :in-path v))))

          (or (= a "--out-vault") (str/starts-with? a "--out-vault="))
          (let [[v next-args err] (parse-flag-value args "--out-vault")]
            (if err
              [opts err]
              (recur next-args (assoc opts :out-vault v))))

          (or (= a "--identity") (str/starts-with? a "--identity="))
          (let [[v next-args err] (parse-flag-value args "--identity")]
            (if err
              [opts err]
              (recur next-args (assoc opts :identity-file v))))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- parse-remote-name-opts
  [args]
  (loop [args args
         opts {:json? false
               :name nil}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          (nil? (:name opts))
          (recur (rest args) (assoc opts :name a))

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- parse-remote-upsert-opts
  [args]
  (loop [args args
         opts {:json? false
               :name nil
               :type nil
               :path nil
               :recipient nil
               :identity nil
               :branch nil
               :bundle-path nil
               :derive-recipient? false
               :no-derive-recipient? false
               :type-set? false
               :path-set? false
               :recipient-set? false
               :identity-set? false
               :branch-set? false
               :bundle-path-set? false}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (= a "--derive-recipient")
          (recur (rest args) (assoc opts :derive-recipient? true))

          (= a "--no-derive-recipient")
          (recur (rest args) (assoc opts :no-derive-recipient? true))

          (or (= a "--type") (str/starts-with? a "--type="))
          (let [[v next-args err] (parse-flag-value args "--type")]
            (if err
              [opts err]
              (recur next-args (assoc opts :type v :type-set? true))))

          (or (= a "--path") (str/starts-with? a "--path="))
          (let [[v next-args err] (parse-flag-value args "--path")]
            (if err
              [opts err]
              (recur next-args (assoc opts :path v :path-set? true))))

          (or (= a "--recipient") (str/starts-with? a "--recipient="))
          (let [[v next-args err] (parse-flag-value args "--recipient")]
            (if err
              [opts err]
              (recur next-args (assoc opts :recipient v :recipient-set? true))))

          (or (= a "--identity") (str/starts-with? a "--identity="))
          (let [[v next-args err] (parse-flag-value args "--identity")]
            (if err
              [opts err]
              (recur next-args (assoc opts :identity v :identity-set? true))))

          (or (= a "--branch") (str/starts-with? a "--branch="))
          (let [[v next-args err] (parse-flag-value args "--branch")]
            (if err
              [opts err]
              (recur next-args (assoc opts :branch v :branch-set? true))))

          (or (= a "--bundle-path") (str/starts-with? a "--bundle-path="))
          (let [[v next-args err] (parse-flag-value args "--bundle-path")]
            (if err
              [opts err]
              (recur next-args (assoc opts :bundle-path v :bundle-path-set? true))))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          (nil? (:name opts))
          (recur (rest args) (assoc opts :name a))

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- parse-sync-init-opts
  [args]
  (loop [args args
         opts {:json? false
               :update? false
               :no-check? false
               :remote-flag nil
               :remote-arg nil
               :type nil
               :path nil
               :recipient nil
               :identity nil
               :branch nil
               :bundle-path nil
               :type-set? false
               :path-set? false
               :recipient-set? false
               :identity-set? false
               :branch-set? false
               :bundle-path-set? false}]
    (if (empty? args)
      (let [remote-flag (some-> (:remote-flag opts) str/trim not-empty)
            remote-arg (some-> (:remote-arg opts) str/trim not-empty)]
        (cond
          (and remote-flag remote-arg (not= remote-flag remote-arg))
          [opts "remote name mismatch between arg and --remote"]

          :else
          [(assoc opts :remote (or remote-flag remote-arg "origin")) nil]))
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (= a "--update")
          (recur (rest args) (assoc opts :update? true))

          (= a "--no-check")
          (recur (rest args) (assoc opts :no-check? true))

          (or (= a "--remote") (str/starts-with? a "--remote="))
          (let [[v next-args err] (parse-flag-value args "--remote")]
            (if err
              [opts err]
              (recur next-args (assoc opts :remote-flag v))))

          (or (= a "--type") (str/starts-with? a "--type="))
          (let [[v next-args err] (parse-flag-value args "--type")]
            (if err
              [opts err]
              (recur next-args (assoc opts :type v :type-set? true))))

          (or (= a "--path") (str/starts-with? a "--path="))
          (let [[v next-args err] (parse-flag-value args "--path")]
            (if err
              [opts err]
              (recur next-args (assoc opts :path v :path-set? true))))

          (or (= a "--recipient") (str/starts-with? a "--recipient="))
          (let [[v next-args err] (parse-flag-value args "--recipient")]
            (if err
              [opts err]
              (recur next-args (assoc opts :recipient v :recipient-set? true))))

          (or (= a "--identity") (str/starts-with? a "--identity="))
          (let [[v next-args err] (parse-flag-value args "--identity")]
            (if err
              [opts err]
              (recur next-args (assoc opts :identity v :identity-set? true))))

          (or (= a "--branch") (str/starts-with? a "--branch="))
          (let [[v next-args err] (parse-flag-value args "--branch")]
            (if err
              [opts err]
              (recur next-args (assoc opts :branch v :branch-set? true))))

          (or (= a "--bundle-path") (str/starts-with? a "--bundle-path="))
          (let [[v next-args err] (parse-flag-value args "--bundle-path")]
            (if err
              [opts err]
              (recur next-args (assoc opts :bundle-path v :bundle-path-set? true))))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          (nil? (:remote-arg opts))
          (recur (rest args) (assoc opts :remote-arg a))

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- parse-sync-status-opts
  [args]
  (loop [args args
         opts {:json? false
               :terse? false
               :strict? false
               :stale-threshold nil
               :stale-threshold-ms 0
               :remote nil}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (= a "--terse")
          (recur (rest args) (assoc opts :terse? true))

          (= a "--strict")
          (recur (rest args) (assoc opts :strict? true))

          (or (= a "--stale-threshold") (str/starts-with? a "--stale-threshold="))
          (let [[v next-args err] (parse-flag-value args "--stale-threshold")]
            (if err
              [opts err]
              (let [[ms parse-err] (parse-duration-ms v)]
                (if parse-err
                  [opts parse-err]
                  (recur next-args (assoc opts
                                          :stale-threshold v
                                          :stale-threshold-ms ms))))))

          (or (= a "--remote") (str/starts-with? a "--remote="))
          (let [[v next-args err] (parse-flag-value args "--remote")]
            (if err
              [opts err]
              (recur next-args (assoc opts :remote v))))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- parse-sync-transfer-opts
  [args]
  (loop [args args
         opts {:json? false
               :dry-run? false
               :force? false
               :reconcile? false
               :no-backup? false
               :lock-wait nil
               :lock-wait-ms 0
               :break-stale-lock-after nil
               :break-stale-lock-after-ms 0
               :passphrase-cmd nil
               :passphrase-stdin? false
               :remote nil}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (= a "--dry-run")
          (recur (rest args) (assoc opts :dry-run? true))

          (= a "--force")
          (recur (rest args) (assoc opts :force? true))

          (= a "--reconcile")
          (recur (rest args) (assoc opts :reconcile? true))

          (= a "--no-backup")
          (recur (rest args) (assoc opts :no-backup? true))

          (or (= a "--lock-wait") (str/starts-with? a "--lock-wait="))
          (let [[v next-args err] (parse-flag-value args "--lock-wait")]
            (if err
              [opts err]
              (let [[ms parse-err] (parse-duration-ms v)]
                (if parse-err
                  [opts parse-err]
                  (recur next-args (assoc opts
                                          :lock-wait v
                                          :lock-wait-ms ms))))))

          (or (= a "--break-stale-lock-after") (str/starts-with? a "--break-stale-lock-after="))
          (let [[v next-args err] (parse-flag-value args "--break-stale-lock-after")]
            (if err
              [opts err]
              (let [[ms parse-err] (parse-duration-ms v)]
                (if parse-err
                  [opts parse-err]
                  (recur next-args (assoc opts
                                          :break-stale-lock-after v
                                          :break-stale-lock-after-ms ms))))))

          (= a "--passphrase-stdin")
          (recur (rest args) (assoc opts :passphrase-stdin? true))

          (or (= a "--passphrase-cmd") (str/starts-with? a "--passphrase-cmd="))
          (let [[v next-args err] (parse-flag-value args "--passphrase-cmd")]
            (if err
              [opts err]
              (recur next-args (assoc opts :passphrase-cmd v))))

          (or (= a "--remote") (str/starts-with? a "--remote="))
          (let [[v next-args err] (parse-flag-value args "--remote")]
            (if err
              [opts err]
              (recur next-args (assoc opts :remote v))))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- parse-sync-auto-opts
  [args]
  (loop [args args
         opts {:json? false
               :terse? false
               :dry-run? false
               :check? false
               :no-doctor? false
               :strict? false
               :allow-missing-vault? false
               :stale-threshold nil
               :stale-threshold-ms 0
               :profile nil
               :bundle-in nil
               :identity nil
               :force? false
               :reconcile? false
               :passphrase-cmd nil
               :passphrase-stdin? false
               :remote nil}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (= a "--terse")
          (recur (rest args) (assoc opts :terse? true))

          (= a "--dry-run")
          (recur (rest args) (assoc opts :dry-run? true))

          (= a "--check")
          (recur (rest args) (assoc opts :check? true))

          (= a "--no-doctor")
          (recur (rest args) (assoc opts :no-doctor? true))

          (= a "--strict")
          (recur (rest args) (assoc opts :strict? true))

          (= a "--allow-missing-vault")
          (recur (rest args) (assoc opts :allow-missing-vault? true))

          (or (= a "--stale-threshold") (str/starts-with? a "--stale-threshold="))
          (let [[v next-args err] (parse-flag-value args "--stale-threshold")]
            (if err
              [opts err]
              (let [[ms parse-err] (parse-duration-ms v)]
                (if parse-err
                  [opts parse-err]
                  (recur next-args (assoc opts
                                          :stale-threshold v
                                          :stale-threshold-ms ms))))))

          (or (= a "--profile") (str/starts-with? a "--profile="))
          (let [[v next-args err] (parse-flag-value args "--profile")]
            (if err
              [opts err]
              (recur next-args (assoc opts :profile v))))

          (or (= a "--bundle-in") (str/starts-with? a "--bundle-in="))
          (let [[v next-args err] (parse-flag-value args "--bundle-in")]
            (if err
              [opts err]
              (recur next-args (assoc opts :bundle-in v))))

          (or (= a "--identity") (str/starts-with? a "--identity="))
          (let [[v next-args err] (parse-flag-value args "--identity")]
            (if err
              [opts err]
              (recur next-args (assoc opts :identity v))))

          (= a "--force")
          (recur (rest args) (assoc opts :force? true))

          (= a "--reconcile")
          (recur (rest args) (assoc opts :reconcile? true))

          (= a "--passphrase-stdin")
          (recur (rest args) (assoc opts :passphrase-stdin? true))

          (or (= a "--passphrase-cmd") (str/starts-with? a "--passphrase-cmd="))
          (let [[v next-args err] (parse-flag-value args "--passphrase-cmd")]
            (if err
              [opts err]
              (recur next-args (assoc opts :passphrase-cmd v))))

          (or (= a "--remote") (str/starts-with? a "--remote="))
          (let [[v next-args err] (parse-flag-value args "--remote")]
            (if err
              [opts err]
              (recur next-args (assoc opts :remote v))))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- parse-sync-reset-baseline-opts
  [args]
  (loop [args args
         opts {:json? false
               :remote nil
               :clear? false
               :to-remote? false
               :rev nil
               :passphrase-cmd nil
               :passphrase-stdin? false
               :yes? false}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (= a "--clear")
          (recur (rest args) (assoc opts :clear? true))

          (= a "--to-remote")
          (recur (rest args) (assoc opts :to-remote? true))

          (or (= a "--rev") (str/starts-with? a "--rev="))
          (let [[v next-args err] (parse-flag-value args "--rev")]
            (if err
              [opts err]
              (recur next-args (assoc opts :rev v))))

          (= a "--yes")
          (recur (rest args) (assoc opts :yes? true))

          (= a "--passphrase-stdin")
          (recur (rest args) (assoc opts :passphrase-stdin? true))

          (or (= a "--passphrase-cmd") (str/starts-with? a "--passphrase-cmd="))
          (let [[v next-args err] (parse-flag-value args "--passphrase-cmd")]
            (if err
              [opts err]
              (recur next-args (assoc opts :passphrase-cmd v))))

          (or (= a "--remote") (str/starts-with? a "--remote="))
          (let [[v next-args err] (parse-flag-value args "--remote")]
            (if err
              [opts err]
              (recur next-args (assoc opts :remote v))))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- parse-sync-restore-opts
  [args]
  (loop [args args
         opts {:json? false
               :no-backup? false
               :backup nil}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (= a "--no-backup")
          (recur (rest args) (assoc opts :no-backup? true))

          (or (= a "--backup") (str/starts-with? a "--backup="))
          (let [[v next-args err] (parse-flag-value args "--backup")]
            (if err
              [opts err]
              (recur next-args (assoc opts :backup v))))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- parse-sync-changes-opts
  [args]
  (loop [args args
         opts {:json? false
               :terse? false
               :passphrase-cmd nil
               :passphrase-stdin? false
               :remote nil}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (= a "--terse")
          (recur (rest args) (assoc opts :terse? true))

          (= a "--passphrase-stdin")
          (recur (rest args) (assoc opts :passphrase-stdin? true))

          (or (= a "--passphrase-cmd") (str/starts-with? a "--passphrase-cmd="))
          (let [[v next-args err] (parse-flag-value args "--passphrase-cmd")]
            (if err
              [opts err]
              (recur next-args (assoc opts :passphrase-cmd v))))

          (or (= a "--remote") (str/starts-with? a "--remote="))
          (let [[v next-args err] (parse-flag-value args "--remote")]
            (if err
              [opts err]
              (recur next-args (assoc opts :remote v))))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- parse-sync-resolve-opts
  [args]
  (loop [args args
         opts {:json? false
               :take nil
               :keys []
               :passphrase-cmd nil
               :passphrase-stdin? false
               :remote nil}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (= a "--passphrase-stdin")
          (recur (rest args) (assoc opts :passphrase-stdin? true))

          (or (= a "--passphrase-cmd") (str/starts-with? a "--passphrase-cmd="))
          (let [[v next-args err] (parse-flag-value args "--passphrase-cmd")]
            (if err
              [opts err]
              (recur next-args (assoc opts :passphrase-cmd v))))

          (or (= a "--remote") (str/starts-with? a "--remote="))
          (let [[v next-args err] (parse-flag-value args "--remote")]
            (if err
              [opts err]
              (recur next-args (assoc opts :remote v))))

          (or (= a "--take") (str/starts-with? a "--take="))
          (let [[v next-args err] (parse-flag-value args "--take")]
            (if err
              [opts err]
              (recur next-args (assoc opts :take v))))

          (or (= a "--key") (str/starts-with? a "--key="))
          (let [[v next-args err] (parse-flag-value args "--key")]
            (if err
              [opts err]
              (recur next-args (update opts :keys conj v))))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- parse-sync-preflight-opts
  [args]
  (loop [args args
         opts {:json? false
               :strict? false
               :allow-missing-vault? false
               :stale-threshold nil
               :stale-threshold-ms 0
               :profile nil
               :bundle-in nil
               :identity nil
               :only []
               :skip []
               :remote nil}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (= a "--strict")
          (recur (rest args) (assoc opts :strict? true))

          (= a "--allow-missing-vault")
          (recur (rest args) (assoc opts :allow-missing-vault? true))

          (or (= a "--stale-threshold") (str/starts-with? a "--stale-threshold="))
          (let [[v next-args err] (parse-flag-value args "--stale-threshold")]
            (if err
              [opts err]
              (let [[ms parse-err] (parse-duration-ms v)]
                (if parse-err
                  [opts parse-err]
                  (recur next-args (assoc opts
                                          :stale-threshold v
                                          :stale-threshold-ms ms))))))

          (or (= a "--profile") (str/starts-with? a "--profile="))
          (let [[v next-args err] (parse-flag-value args "--profile")]
            (if err
              [opts err]
              (recur next-args (assoc opts :profile v))))

          (or (= a "--bundle-in") (str/starts-with? a "--bundle-in="))
          (let [[v next-args err] (parse-flag-value args "--bundle-in")]
            (if err
              [opts err]
              (recur next-args (assoc opts :bundle-in v))))

          (or (= a "--identity") (str/starts-with? a "--identity="))
          (let [[v next-args err] (parse-flag-value args "--identity")]
            (if err
              [opts err]
              (recur next-args (assoc opts :identity v))))

          (or (= a "--only") (str/starts-with? a "--only="))
          (let [[v next-args err] (parse-flag-value args "--only")]
            (if err
              [opts err]
              (recur next-args (update opts :only conj v))))

          (or (= a "--skip") (str/starts-with? a "--skip="))
          (let [[v next-args err] (parse-flag-value args "--skip")]
            (if err
              [opts err]
              (recur next-args (update opts :skip conj v))))

          (or (= a "--remote") (str/starts-with? a "--remote="))
          (let [[v next-args err] (parse-flag-value args "--remote")]
            (if err
              [opts err]
              (recur next-args (assoc opts :remote v))))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- parse-sync-unlock-opts
  [args]
  (loop [args args
         opts {:json? false
               :if-older-than nil
               :if-older-than-ms 0
               :yes? false
               :remote nil}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (= a "--yes")
          (recur (rest args) (assoc opts :yes? true))

          (or (= a "--if-older-than") (str/starts-with? a "--if-older-than="))
          (let [[v next-args err] (parse-flag-value args "--if-older-than")]
            (if err
              [opts err]
              (let [[ms parse-err] (parse-duration-ms v)]
                (if parse-err
                  [opts parse-err]
                  (recur next-args (assoc opts
                                          :if-older-than v
                                          :if-older-than-ms ms))))))

          (or (= a "--remote") (str/starts-with? a "--remote="))
          (let [[v next-args err] (parse-flag-value args "--remote")]
            (if err
              [opts err]
              (recur next-args (assoc opts :remote v))))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- parse-init-ci-pr-safety-opts
  [args]
  (loop [args args
         opts (merge {:json? false
                      :force? false
                      :out init/default-ci-pr-safety-workflow-path}
                     (init/default-ci-pr-safety-options))]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (= a "--force")
          (recur (rest args) (assoc opts :force? true))

          (or (= a "--out") (str/starts-with? a "--out="))
          (let [[v next-args err] (parse-flag-value args "--out")]
            (if err
              [opts err]
              (recur next-args (assoc opts :out v))))

          (or (= a "--profile") (str/starts-with? a "--profile="))
          (let [[v next-args err] (parse-flag-value args "--profile")]
            (if err
              [opts err]
              (recur next-args (assoc opts :profile v))))

          (or (= a "--command") (str/starts-with? a "--command="))
          (let [[v next-args err] (parse-flag-value args "--command")]
            (if err
              [opts err]
              (recur next-args (assoc opts :command v))))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- parse-init-ci-deploy-opts
  [args]
  (loop [args args
         opts (merge {:json? false
                      :force? false
                      :out init/default-ci-deploy-workflow-path}
                     (init/default-ci-deploy-options))]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (= a "--force")
          (recur (rest args) (assoc opts :force? true))

          (or (= a "--out") (str/starts-with? a "--out="))
          (let [[v next-args err] (parse-flag-value args "--out")]
            (if err
              [opts err]
              (recur next-args (assoc opts :out v))))

          (or (= a "--profile") (str/starts-with? a "--profile="))
          (let [[v next-args err] (parse-flag-value args "--profile")]
            (if err
              [opts err]
              (recur next-args (assoc opts :profile v))))

          (or (= a "--deploy-command") (str/starts-with? a "--deploy-command="))
          (let [[v next-args err] (parse-flag-value args "--deploy-command")]
            (if err
              [opts err]
              (recur next-args (assoc opts :deploy-command v))))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- parse-init-ci-sync-gate-opts
  [args]
  (loop [args args
         opts (merge {:json? false
                      :force? false
                      :out init/default-ci-sync-gate-workflow-path}
                     (init/default-ci-sync-gate-options))]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (= a "--force")
          (recur (rest args) (assoc opts :force? true))

          (or (= a "--out") (str/starts-with? a "--out="))
          (let [[v next-args err] (parse-flag-value args "--out")]
            (if err
              [opts err]
              (recur next-args (assoc opts :out v))))

          (or (= a "--remote-name") (str/starts-with? a "--remote-name="))
          (let [[v next-args err] (parse-flag-value args "--remote-name")]
            (if err
              [opts err]
              (recur next-args (assoc opts :remote-name v))))

          (or (= a "--remote-type") (str/starts-with? a "--remote-type="))
          (let [[v next-args err] (parse-flag-value args "--remote-type")]
            (if err
              [opts err]
              (recur next-args (assoc opts :remote-type v))))

          (or (= a "--remote-path") (str/starts-with? a "--remote-path="))
          (let [[v next-args err] (parse-flag-value args "--remote-path")]
            (if err
              [opts err]
              (recur next-args (assoc opts :remote-path v))))

          (or (= a "--remote-branch") (str/starts-with? a "--remote-branch="))
          (let [[v next-args err] (parse-flag-value args "--remote-branch")]
            (if err
              [opts err]
              (recur next-args (assoc opts :remote-branch v))))

          (or (= a "--remote-bundle-path") (str/starts-with? a "--remote-bundle-path="))
          (let [[v next-args err] (parse-flag-value args "--remote-bundle-path")]
            (if err
              [opts err]
              (recur next-args (assoc opts :remote-bundle-path v))))

          (or (= a "--local-bundle") (str/starts-with? a "--local-bundle="))
          (let [[v next-args err] (parse-flag-value args "--local-bundle")]
            (if err
              [opts err]
              (recur next-args (assoc opts :local-bundle v))))

          (or (= a "--profile") (str/starts-with? a "--profile="))
          (let [[v next-args err] (parse-flag-value args "--profile")]
            (if err
              [opts err]
              (recur next-args (assoc opts :profile v))))

          (or (= a "--stale-threshold") (str/starts-with? a "--stale-threshold="))
          (let [[v next-args err] (parse-flag-value args "--stale-threshold")]
            (if err
              [opts err]
              (recur next-args (assoc opts :stale-threshold v))))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- plan-error
  [json? reason message]
  (if json?
    (error-json exit-code/code-plan-failed reason message)
    (error-text exit-code/code-plan-failed message)))

(defn- vault-error-code
  [reason]
  (case reason
    "vault_not_found" exit-code/code-vault-not-found
    "wrong_passphrase" exit-code/code-wrong-passphrase
    exit-code/code-vault-failed))

(defn- vault-error-reason
  [e]
  (or (:reason (ex-data e)) reasons/reason-vault-failed))

(defn- vault-error-result
  [json? e]
  (let [reason (vault-error-reason e)
        code (vault-error-code reason)
        message (.getMessage e)]
    (if json?
      (error-json code reason message)
      (error-text code message))))

(defn- secret-error-code
  [reason]
  (case reason
    "secret_not_found" exit-code/code-secret-not-found
    "secret_exists" exit-code/code-secret-exists
    "vault_not_found" exit-code/code-vault-not-found
    "wrong_passphrase" exit-code/code-wrong-passphrase
    1))

(defn- secret-error-reason
  [e]
  (or (:reason (ex-data e)) reasons/reason-secret-failed))

(defn- secret-error-result
  [json? e]
  (let [reason (secret-error-reason e)
        code (secret-error-code reason)
        message (.getMessage e)]
    (if json?
      (error-json code reason message)
      (error-text code message))))

(defn- config-error-result
  [json? e]
  (let [reason (or (:reason (ex-data e)) reasons/reason-config-failed)
        message (.getMessage e)]
    (if json?
      (error-json exit-code/code-config-failed reason message)
      (error-text exit-code/code-config-failed message))))

(defn- config-success
  [json? payload text]
  (if json?
    (success-json payload)
    (result {:exit-code 0
             :stdout (str text "\n")})))

(defn- parse-config-show-opts
  [args]
  (loop [args args
         opts {:pretty true}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--pretty=true")
          (recur (rest args) (assoc opts :pretty true))

          (= a "--pretty=false")
          (recur (rest args) (assoc opts :pretty false))

          (str/starts-with? a "--pretty=")
          (let [v (subs a (count "--pretty="))]
            (cond
              (= v "true") (recur (rest args) (assoc opts :pretty true))
              (= v "false") (recur (rest args) (assoc opts :pretty false))
              :else [opts "invalid value for --pretty (expected true|false)"]))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- parse-config-unlock-set-opts
  [args]
  (let [[opt-args cmd] (split-before-double-dash args)]
    (loop [args opt-args
           opts {:json? false
                 :method nil
                 :command (vec cmd)}]
      (if (empty? args)
        [opts nil]
        (let [a (first args)]
          (cond
            (= a "--json")
            (recur (rest args) (assoc opts :json? true))

            (str/starts-with? a "-")
            [opts (str "unknown flag " a)]

            (nil? (:method opts))
            (recur (rest args) (assoc opts :method a))

            :else
            [opts (str "unexpected argument " (pr-str a))]))))))

(defn- parse-config-vault-set-opts
  [args]
  (loop [args args
         opts {:json? false
               :vault-path nil}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          (nil? (:vault-path opts))
          (recur (rest args) (assoc opts :vault-path a))

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn- resolve-passphrase!
  [ctx opts]
  (passphrase/resolve-passphrase
   {:config-path (:config-path ctx)
    :stdin (or (:stdin ctx) System/in)}
   {:passphrase-cmd (:passphrase-cmd opts)
    :passphrase-stdin? (:passphrase-stdin? opts)}))

(defn- read-secret-value-from-stdin
  [ctx]
  (let [in (or (:stdin ctx) System/in)
        baos (java.io.ByteArrayOutputStream.)]
    (io/copy in baos)
    (String. (.toByteArray baos) "UTF-8")))

(defn- read-secret-value
  [ctx {:keys [stdin? value]}]
  (cond
    (and stdin? (some? value))
    (throw (ex-info "provide either --stdin or --value, not both" {:reason reasons/reason-secret-failed}))

    stdin?
    (read-secret-value-from-stdin ctx)

    (some? value)
    (str value)

    :else
    (if-let [console (System/console)]
      (let [chars (.readPassword console "Secret value: " (object-array 0))
            v (some-> chars String.)]
        (if (str/blank? v)
          (throw (ex-info "empty secret value" {:reason reasons/reason-empty-secret-value}))
          v))
      (throw (ex-info "no secret value provided (use --stdin or run in a tty)"
                      {:reason reasons/reason-missing-secret-value})))))

(defn- handle-map-lint
  [ctx args]
  (let [[opts parse-error] (parse-map-lint-opts args)
        mode (:mode opts)
        report
        (cond
          parse-error
          (map-lint/invalid-input-report mode parse-error)

          :else
          (try
            (let [resolved-path (resolve-map-path! opts reasons/reason-plan-failed)
                  source ((:read-file ctx) resolved-path)]
              (map-lint/lint-source {:source source :mode mode}))
            (catch Exception e
              (map-lint/invalid-input-report mode (.getMessage e)))))
        report (map-lint/apply-strict report (:strict? opts))
        payload (assoc report :action "map_lint")
        exit-code (:exit_code report)]
    (if (:json? opts)
      (result {:exit-code exit-code
               :stdout (json-line payload)})
      (result {:exit-code exit-code
               :stdout (str (map-lint/render-report-text report) "\n")}))))

(defn- handle-map
  [ctx args]
  (let [args (vec args)]
    (cond
      (or (empty? args) (help-arg? (first args)))
      (help-result map-usage)

      (= "lint" (first args))
      (handle-map-lint ctx (rest args))

      :else
      (error-with-help 1 (str "unknown map command " (pr-str (first args))) map-usage))))

(defn- handle-plan
  [ctx args]
  (let [[opts parse-error] (parse-plan-opts args)
        json? (:json? opts)]
    (cond
      parse-error
      (plan-error json? reasons/reason-plan-failed parse-error)

      :else
      (try
        (let [{:keys [request env-paths]} (resolve-mappings! ctx opts reasons/reason-plan-failed)
              {:keys [against-request against-env-paths against-label]} (resolve-against-mappings! ctx opts reasons/reason-plan-failed)
              _ (when against-request
                  (plan/plan-from-mappings {:request request
                                            :env-paths env-paths
                                            :mode (:mode opts)
                                            :command (:command opts)}))
              payload
              (if against-request
                (try
                  (plan/plan-from-mappings {:request request
                                            :env-paths env-paths
                                            :mode (:mode opts)
                                            :command (:command opts)
                                            :against-request against-request
                                            :against-env-paths against-env-paths
                                            :against-label against-label})
                  (catch clojure.lang.ExceptionInfo e
                    (let [reason (:reason (ex-data e))]
                      (if (or (= reason reasons/reason-envpath-requires-projected-files)
                              (= reason reasons/reason-envpath-missing-projected-file))
                        (throw (ex-info (format "against spec is invalid: %s" (.getMessage e))
                                        {:reason reasons/reason-invalid-against-spec}
                                        e))
                        (throw e)))))
                (plan/plan-from-mappings {:request request
                                          :env-paths env-paths
                                          :mode (:mode opts)
                                          :command (:command opts)}))]
          (if json?
            (success-json payload)
            (result {:exit-code 0
                     :stdout (str (plan/render-plan-text payload) "\n")})))
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)
                reason (or (:reason data) reasons/reason-plan-failed)]
            (plan-error json? reason (.getMessage e))))
        (catch Exception e
          (plan-error json? reasons/reason-plan-failed (.getMessage e)))))))

(defn- handle-version
  [args]
  (let [json? (some #{"--json"} args)
        invalid (seq (remove #{"--json"} args))]
    (cond
      invalid
      (error-text 1 (str "unknown arguments: " (str/join " " invalid)))

      json?
      (success-json (version/payload))

      :else
      (let [{:keys [version]} (version/payload)]
        (result {:exit-code 0
                 :stdout (str "kimen " version "\n")})))))

(defn- config-path
  [ctx args]
  (let [[opts parse-error] (parse-json-only-opts args)
        json? (:json? opts)]
    (if parse-error
      (config-error-result json? (ex-info parse-error {:reason reasons/reason-config-failed}))
      (try
        (let [path (config/resolve-config-path (:config-path ctx))]
          (if json?
            (success-json {:ok true
                           :action "config_path"
                           :exit_code 0
                           :path path})
            (result {:exit-code 0
                     :stdout (str path "\n")})))
        (catch Exception e
          (config-error-result json? e))))))

(defn- config-show
  [ctx args]
  (let [[opts parse-error] (parse-config-show-opts args)]
    (if parse-error
      (config-error-result false (ex-info parse-error {:reason reasons/reason-config-failed}))
      (try
        (let [cfg (config/config-show (:config-path ctx))
              body (if (:pretty opts)
                     (json/pretty-write-str cfg)
                     (json/write-str cfg))]
          (result {:exit-code 0
                   :stdout (str body "\n")}))
        (catch Exception e
          (config-error-result false e))))))

(defn- config-vault-set
  [ctx args]
  (let [[opts parse-error] (parse-config-vault-set-opts args)
        json? (:json? opts)]
    (cond
      parse-error
      (config-error-result json? (ex-info parse-error {:reason reasons/reason-config-failed}))

      (str/blank? (:vault-path opts))
      (config-error-result json? (ex-info "missing vault path" {:reason reasons/reason-config-failed}))

      :else
      (try
        (config/config-vault-set! (:config-path ctx) (:vault-path opts))
        (config-success json?
                        {:ok true
                         :action "config_vault_set"
                         :exit_code 0
                         :path (:vault-path opts)}
                        (:vault-path opts))
        (catch Exception e
          (config-error-result json? e))))))

(defn- config-vault-show
  [ctx args]
  (let [[opts parse-error] (parse-json-only-opts args)
        json? (:json? opts)]
    (if parse-error
      (config-error-result json? (ex-info parse-error {:reason reasons/reason-config-failed}))
      (try
        (let [path (config/config-vault-show (:config-path ctx))]
          (config-success json?
                          {:ok true
                           :action "config_vault_show"
                           :exit_code 0
                           :path path}
                          (or path "")))
        (catch Exception e
          (config-error-result json? e))))))

(defn- config-vault-clear
  [ctx args]
  (let [[opts parse-error] (parse-json-only-opts args)
        json? (:json? opts)]
    (if parse-error
      (config-error-result json? (ex-info parse-error {:reason reasons/reason-config-failed}))
      (try
        (config/config-vault-clear! (:config-path ctx))
        (config-success json?
                        {:ok true
                         :action "config_vault_clear"
                         :exit_code 0}
                        "cleared")
        (catch Exception e
          (config-error-result json? e))))))

(defn- config-unlock-set
  [ctx args]
  (let [[opts parse-error] (parse-config-unlock-set-opts args)
        json? (:json? opts)]
    (cond
      parse-error
      (config-error-result json? (ex-info parse-error {:reason reasons/reason-config-failed}))

      (str/blank? (:method opts))
      (config-error-result json? (ex-info "missing unlock method" {:reason reasons/reason-config-failed}))

      :else
      (try
        (config/config-unlock-set! (:config-path ctx) (:method opts) (:command opts))
        (config-success json?
                        {:ok true
                         :action "config_unlock_set"
                         :exit_code 0
                         :method (str/lower-case (:method opts))
                         :exec (when (seq (:command opts)) (:command opts))}
                        (str/lower-case (:method opts)))
        (catch Exception e
          (config-error-result json? e))))))

(defn- config-unlock-show
  [ctx args]
  (let [[opts parse-error] (parse-json-only-opts args)
        json? (:json? opts)]
    (if parse-error
      (config-error-result json? (ex-info parse-error {:reason reasons/reason-config-failed}))
      (try
        (let [{:keys [method exec]} (config/config-unlock-show (:config-path ctx))]
          (config-success json?
                          {:ok true
                           :action "config_unlock_show"
                           :exit_code 0
                           :method method
                           :exec exec}
                          (if (seq exec)
                            (str method " -- " (str/join " " exec))
                            method)))
        (catch Exception e
          (config-error-result json? e))))))

(defn- config-unlock-clear
  [ctx args]
  (let [[opts parse-error] (parse-json-only-opts args)
        json? (:json? opts)]
    (if parse-error
      (config-error-result json? (ex-info parse-error {:reason reasons/reason-config-failed}))
      (try
        (config/config-unlock-clear! (:config-path ctx))
        (config-success json?
                        {:ok true
                         :action "config_unlock_clear"
                         :exit_code 0}
                        "cleared")
        (catch Exception e
          (config-error-result json? e))))))

(defn- handle-config
  [ctx args]
  (let [args (vec args)]
    (cond
      (or (empty? args) (help-arg? (first args)))
      (help-result config-usage)

      (= ["path"] args) (config-path ctx [])
      (and (= "path" (first args))) (config-path ctx (rest args))

      (= ["show"] args) (config-show ctx [])
      (and (= "show" (first args))) (config-show ctx (rest args))

      (and (= "vault" (first args)) (= "set" (second args)))
      (config-vault-set ctx (drop 2 args))
      (and (= "vault" (first args)) (= "show" (second args)))
      (config-vault-show ctx (drop 2 args))
      (and (= "vault" (first args)) (= "clear" (second args)))
      (config-vault-clear ctx (drop 2 args))

      (and (= "unlock" (first args)) (= "set" (second args)))
      (config-unlock-set ctx (drop 2 args))
      (and (= "unlock" (first args)) (= "show" (second args)))
      (config-unlock-show ctx (drop 2 args))
      (and (= "unlock" (first args)) (= "clear" (second args)))
      (config-unlock-clear ctx (drop 2 args))

      :else
      (error-with-help 1 (str "unknown config command " (pr-str (first args))) config-usage))))

(defn- remote-error-result
  [json? e]
  (let [reason (or (:reason (ex-data e)) reasons/reason-remote-failed)]
    (if json?
      (error-json exit-code/code-remote-failed reason (.getMessage e))
      (error-text exit-code/code-remote-failed (.getMessage e)))))

(defn- validate-remote-name!
  [name]
  (let [name (some-> name str/trim)]
    (when (str/blank? name)
      (throw (ex-info "empty remote name" {:reason reasons/reason-empty-remote-name})))
    (when-not (re-matches remote-name-re name)
      (throw (ex-info (format "invalid remote name %s" (pr-str name))
                      {:reason reasons/reason-invalid-remote-name})))
    name))

(defn- build-remote-map
  [{:keys [name type path recipient identity branch bundle-path]}]
  (let [type (config/normalize-remote-type type)
        path (some-> path str/trim)
        recipient (some-> recipient str/trim)
        identity (some-> identity str/trim)
        branch (some-> branch str/trim)
        bundle-path (some-> bundle-path str/trim)
        base (cond-> {"name" (some-> name str/trim)
                      "type" type
                      "path" path}
               (not (str/blank? recipient)) (assoc "recipient" recipient)
               (not (str/blank? identity)) (assoc "identity" identity))]
    (if (= type "git")
      (cond-> base
        (not (str/blank? branch)) (assoc "branch" branch)
        (not (str/blank? bundle-path)) (assoc "bundle_path" bundle-path))
      base)))

(defn- handle-remote-list
  [ctx args]
  (let [[opts parse-error] (parse-json-only-opts args)
        json? (:json? opts)]
    (if parse-error
      (remote-error-result json? (ex-info parse-error {:reason reasons/reason-remote-failed}))
      (try
        (let [remotes (config/config-remote-list (:config-path ctx))]
          (if json?
            (success-json {:ok true
                           :action "remote_list"
                           :exit_code 0
                           :remotes remotes
                           :count (count remotes)})
            (let [out (apply str
                             (for [r remotes]
                               (if (= "git" (get r "type"))
                                 (format "%s\t%s\t%s@%s:%s\n"
                                         (get r "name")
                                         (get r "type")
                                         (get r "path")
                                         (get r "branch")
                                         (get r "bundle_path"))
                                 (format "%s\t%s\t%s\n"
                                         (get r "name")
                                         (get r "type")
                                         (get r "path")))))]
              (result {:exit-code 0
                       :stdout out}))))
        (catch Exception e
          (remote-error-result json? e))))))

(defn- handle-remote-get
  [ctx args]
  (let [[opts parse-error] (parse-remote-name-opts args)
        json? (:json? opts)]
    (if parse-error
      (remote-error-result json? (ex-info parse-error {:reason reasons/reason-remote-failed}))
      (try
        (let [name (validate-remote-name! (:name opts))
              remote (config/config-remote-get (:config-path ctx) name)]
          (if json?
            (success-json {:ok true
                           :action "remote_get"
                           :exit_code 0
                           :name name
                           :remote remote})
            (let [recipient (some-> (get remote "recipient") str/trim not-empty)
                  identity (some-> (get remote "identity") str/trim not-empty)
                  lines [(str "name: " (get remote "name"))
                         (str "type: " (get remote "type"))
                         (str "path: " (get remote "path"))
                         (when (= "git" (get remote "type"))
                           (str "branch: " (get remote "branch")))
                         (when (= "git" (get remote "type"))
                           (str "bundle_path: " (get remote "bundle_path")))
                         (if recipient
                           (str "recipient: " recipient)
                           "recipient: (none)")
                         (if identity
                           (str "identity: " identity)
                           "identity: (none)")]]
              (result {:exit-code 0
                       :stdout (str (str/join "\n" (remove nil? lines)) "\n")}))))
        (catch Exception e
          (remote-error-result json? e))))))

(defn- handle-remote-rm
  [ctx args]
  (let [[opts parse-error] (parse-remote-name-opts args)
        json? (:json? opts)]
    (if parse-error
      (remote-error-result json? (ex-info parse-error {:reason reasons/reason-remote-failed}))
      (try
        (let [name (validate-remote-name! (:name opts))]
          (config/config-remote-remove! (:config-path ctx) name)
          (if json?
            (success-json {:ok true
                           :action "remote_rm"
                           :exit_code 0
                           :name name})
            (result {:exit-code 0
                     :stdout (format "ok (remote %s removed)\n" name)})))
        (catch Exception e
          (remote-error-result json? e))))))

(defn- handle-remote-add
  [ctx args]
  (let [[opts parse-error] (parse-remote-upsert-opts args)
        json? (:json? opts)]
    (if parse-error
      (remote-error-result json? (ex-info parse-error {:reason reasons/reason-remote-failed}))
      (try
        (let [name (validate-remote-name! (:name opts))
              type (config/normalize-remote-type (or (:type opts) "fs"))
              _ (when-not (#{"fs" "git"} type)
                  (throw (ex-info (format "unsupported remote type %s (expected fs or git)" (pr-str type))
                                  {:reason reasons/reason-unsupported-remote-type})))
              _ (when (and (:derive-recipient? opts) (:no-derive-recipient? opts))
                  (throw (ex-info "--derive-recipient cannot be combined with --no-derive-recipient"
                                  {:reason reasons/reason-conflicting-derive-flags})))
              _ (when (and (:derive-recipient? opts)
                           (:recipient-set? opts)
                           (not (str/blank? (:recipient opts))))
                  (throw (ex-info "--derive-recipient cannot be combined with --recipient"
                                  {:reason reasons/reason-conflicting-derive-recipient-inputs})))
              _ (when (and (not= type "git")
                           (or (:branch-set? opts) (:bundle-path-set? opts)))
                  (throw (ex-info "--branch/--bundle-path are only valid for --type git"
                                  {:reason reasons/reason-git-fields-require-git-type})))
              path (some-> (:path opts) str/trim)
              _ (when (str/blank? path)
                  (throw (ex-info "--path is required" {:reason reasons/reason-missing-path})))
              identity (some-> (:identity opts) str/trim)
              recipient (some-> (:recipient opts) str/trim)
              should-derive? (or (:derive-recipient? opts)
                                 (and (not (:no-derive-recipient? opts))
                                      (str/blank? recipient)
                                      (not (str/blank? identity))))
              _ (when (and should-derive? (str/blank? identity))
                  (throw (ex-info "--derive-recipient requires --identity"
                                  {:reason reasons/reason-missing-identity-for-recipient-derivation})))
              recipient (if should-derive?
                          (config/derive-recipient-from-identity-file identity)
                          recipient)
              remote (build-remote-map {:name name
                                        :type type
                                        :path path
                                        :recipient recipient
                                        :identity identity
                                        :branch (:branch opts)
                                        :bundle-path (:bundle-path opts)})
              remote (config/config-remote-add! (:config-path ctx) remote)]
          (if json?
            (success-json {:ok true
                           :action "remote_add"
                           :exit_code 0
                           :name name
                           :remote remote})
            (result {:exit-code 0
                     :stdout (format "ok (remote %s)\n" name)})))
        (catch Exception e
          (remote-error-result json? e))))))

(defn- handle-remote-set
  [ctx args]
  (let [[opts parse-error] (parse-remote-upsert-opts args)
        json? (:json? opts)]
    (if parse-error
      (remote-error-result json? (ex-info parse-error {:reason reasons/reason-remote-failed}))
      (try
        (let [name (validate-remote-name! (:name opts))
              _ (when (and (not (:type-set? opts))
                           (not (:path-set? opts))
                           (not (:recipient-set? opts))
                           (not (:identity-set? opts))
                           (not (:branch-set? opts))
                           (not (:bundle-path-set? opts))
                           (not (:derive-recipient? opts)))
                  (throw (ex-info "set at least one of --type, --path, --recipient, --identity, --branch, --bundle-path, --derive-recipient"
                                  {:reason reasons/reason-missing-remote-set-fields})))
              _ (when (and (:derive-recipient? opts) (:no-derive-recipient? opts))
                  (throw (ex-info "--derive-recipient cannot be combined with --no-derive-recipient"
                                  {:reason reasons/reason-conflicting-derive-flags})))
              _ (when (and (:derive-recipient? opts) (:recipient-set? opts))
                  (throw (ex-info "--derive-recipient cannot be combined with --recipient"
                                  {:reason reasons/reason-conflicting-derive-recipient-inputs})))
              existing (config/config-remote-get (:config-path ctx) name)
              type (if (:type-set? opts)
                     (config/normalize-remote-type (:type opts))
                     (get existing "type"))
              _ (when-not (#{"fs" "git"} type)
                  (throw (ex-info (format "unsupported remote type %s (expected fs or git)" (pr-str type))
                                  {:reason reasons/reason-unsupported-remote-type})))
              path (if (:path-set? opts)
                     (some-> (:path opts) str/trim)
                     (get existing "path"))
              _ (when (and (:path-set? opts) (str/blank? path))
                  (throw (ex-info "--path cannot be empty" {:reason reasons/reason-empty-path})))
              recipient (if (:recipient-set? opts)
                          (some-> (:recipient opts) str/trim)
                          (some-> (get existing "recipient") str/trim))
              identity (if (:identity-set? opts)
                         (some-> (:identity opts) str/trim)
                         (some-> (get existing "identity") str/trim))
              should-derive? (or (:derive-recipient? opts)
                                 (and (not (:no-derive-recipient? opts))
                                      (not (:recipient-set? opts))
                                      (:identity-set? opts)
                                      (not (str/blank? identity))))
              _ (when (and should-derive? (str/blank? identity))
                  (throw (ex-info "--derive-recipient requires --identity (or existing remote identity)"
                                  {:reason reasons/reason-missing-identity-for-recipient-derivation})))
              recipient (if should-derive?
                          (config/derive-recipient-from-identity-file identity)
                          recipient)
              branch (if (:branch-set? opts)
                       (some-> (:branch opts) str/trim)
                       (some-> (get existing "branch") str/trim))
              bundle-path (if (:bundle-path-set? opts)
                            (some-> (:bundle-path opts) str/trim)
                            (some-> (get existing "bundle_path") str/trim))
              _ (when (and (not= type "git")
                           (or (:branch-set? opts) (:bundle-path-set? opts)))
                  (throw (ex-info "--branch/--bundle-path are only valid for --type git"
                                  {:reason reasons/reason-git-fields-require-git-type})))
              remote-map (build-remote-map {:name name
                                            :type type
                                            :path path
                                            :recipient recipient
                                            :identity identity
                                            :branch branch
                                            :bundle-path bundle-path})
              {:keys [remote baseline-reset?]} (config/config-remote-set! (:config-path ctx) name remote-map)]
          (if json?
            (success-json {:ok true
                           :action "remote_set"
                           :exit_code 0
                           :name name
                           :remote remote
                           :baseline_reset baseline-reset?})
            (result {:exit-code 0
                     :stdout (str (format "ok (remote %s updated)\n" name)
                                  (when baseline-reset?
                                    "sync baseline reset (remote endpoint changed)\n"))})))
        (catch Exception e
          (remote-error-result json? e))))))

(defn- handle-remote
  [ctx args]
  (let [args (vec args)]
    (cond
      (or (empty? args) (help-arg? (first args)))
      (help-result remote-usage)

      (= "add" (first args)) (handle-remote-add ctx (rest args))
      (= "get" (first args)) (handle-remote-get ctx (rest args))
      (= "set" (first args)) (handle-remote-set ctx (rest args))
      (= "list" (first args)) (handle-remote-list ctx (rest args))
      (or (= "rm" (first args))
          (= "remove" (first args))
          (= "delete" (first args))) (handle-remote-rm ctx (rest args))
      :else (error-with-help 1 (str "unknown remote command " (pr-str (first args))) remote-usage))))

(defn- sync-exit-code-for-reason
  [reason]
  (if (sync-state/conflict-reason? reason)
    exit-code/code-sync-conflict
    exit-code/code-sync-failed))

(defn- sync-error-reason
  [e]
  (let [data (ex-data e)
        explicit (:reason data)]
    (cond
      (and (string? explicit) (not= explicit reasons/reason-sync-failed))
      explicit

      :else
      (or (sync-state/infer-error-reason-from-message (.getMessage e))
          explicit
          reasons/reason-sync-failed))))

(defn- detect-sync-conflict
  [last-seen remote-rev has-remote]
  (sync-state/detect-conflict last-seen remote-rev has-remote))

(defn- sync-error-result
  [json? e]
  (let [data (ex-data e)
        reason (sync-error-reason e)
        code (sync-exit-code-for-reason reason)
        recommended-action (or (:recommended_action data)
                               (sync-state/recommended-action-for-reason reason))
        payload (cond-> {:ok false
                         :error (.getMessage e)
                         :exit_code code
                         :reason reason}
                  (some? (:expected_rev data)) (assoc :expected_rev (:expected_rev data))
                  (some? (:actual_rev data)) (assoc :actual_rev (:actual_rev data))
                  (some? recommended-action) (assoc :recommended_action recommended-action))]
    (if json?
      (result {:exit-code code
               :stderr (json-line payload)})
      (error-text code (.getMessage e)))))

(defn- sync-init-next-command
  [remote-name recommended-action]
  (case recommended-action
    "vault_init" "kimen vault init"
    "sync_push" (format "kimen sync push --remote %s" remote-name)
    nil))

(defn- sync-init-check
  [ctx remote-name]
  (let [vault-path (vault-path/resolve-vault-path ctx nil)
        vault-exists? (.exists (io/file vault-path))
        recommended-action (if vault-exists? "sync_push" "vault_init")]
    {:check_ok true
     :recommended_action recommended-action
     :next_command (sync-init-next-command remote-name recommended-action)}))

(defn- fs-remote-bundle-path
  [remote]
  (let [path (some-> (get remote "path") str/trim)
        bundle-path (some-> (get remote "bundle_path") str/trim)
        bundle-path (if (str/blank? bundle-path) "vault.age" bundle-path)
        path (when (some? path) (str/replace path #"/+$" ""))]
    (when-not (str/blank? path)
      (if (str/ends-with? path ".age")
        path
        (str path "/" bundle-path)))))

(defn- remote-type
  [remote]
  (let [t (some-> (get remote "type") str/trim str/lower-case)]
    (if (str/blank? t) "fs" t)))

(defn- remote-supports-push-lock?
  [remote]
  (= "fs" (remote-type remote)))

(defn- hex-bytes
  [^bytes b]
  (apply str (map #(format "%02x" (bit-and % 0xff)) b)))

(defn- file-sha256-hex
  [path]
  (let [bytes (Files/readAllBytes (.toPath (io/file path)))
        digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (hex-bytes digest)))

(defn- file-revision
  [path]
  (let [f (io/file path)]
    (if (.exists f)
      [(file-sha256-hex path) true]
      [nil false])))

(defn- ensure-fs-remote!
  [remote]
  (let [t (remote-type remote)]
    (when-not (= t "fs")
      (throw (ex-info "sync unlock is only supported for fs remotes"
                      {:reason reasons/reason-unlock-requires-fs-remote})))
    remote))

(defn- remote-lock-path
  [remote]
  (when-let [bundle-path (fs-remote-bundle-path remote)]
    (str bundle-path ".lock")))

(defn- lock-age-ms
  [^java.io.File lock-file]
  (max 0 (- (System/currentTimeMillis) (.lastModified lock-file))))

(defn- clear-push-lock-if-allowed!
  [^java.io.File lock-file break-stale-lock-after-ms]
  (when (and (.exists lock-file)
             (pos? break-stale-lock-after-ms)
             (>= (lock-age-ms lock-file) break-stale-lock-after-ms))
    (.delete lock-file)))

(defn- await-push-lock-clear!
  [lock-path lock-wait-ms break-stale-lock-after-ms]
  (let [lock-file (io/file lock-path)
        wait-ms (long (max 0 (or lock-wait-ms 0)))
        break-ms (long (max 0 (or break-stale-lock-after-ms 0)))
        deadline (+ (System/currentTimeMillis) wait-ms)]
    (loop [stale-lock-broken false]
      (let [stale-lock-broken (or stale-lock-broken
                                  (boolean (clear-push-lock-if-allowed! lock-file break-ms)))]
        (if-not (.exists lock-file)
          {:locked? false
           :stale_lock_broken stale-lock-broken}
          (let [now (System/currentTimeMillis)]
            (if (>= now deadline)
              {:locked? true
               :stale_lock_broken stale-lock-broken}
              (do
                (Thread/sleep (long (min 100 (max 1 (- deadline now)))))
                (recur stale-lock-broken)))))))))

(defn- copy-file!
  [src dst]
  (Files/copy (.toPath (io/file src))
              (.toPath (io/file dst))
              (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
  dst)

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

(defn- git-run-raw
  [dir args]
  (let [dir (some-> dir str/trim not-empty)]
    (apply sh/sh
           (concat ["git"]
                   args
                   (cond-> [:env (sanitized-git-env)
                            :out :string
                            :err :string]
                     (some? dir) (into [:dir dir]))))))

(defn- git-run!
  [dir args]
  (let [res (git-run-raw dir args)]
    (if (zero? (:exit res))
      res
      (let [msg (or (not-empty (str/trim (str (:err res))))
                    (not-empty (str/trim (str (:out res))))
                    "git command failed")]
        (throw (ex-info (format "git %s: %s" (str/join " " args) msg)
                        {:reason reasons/reason-sync-failed
                         :git_args (vec args)
                         :git_exit (:exit res)
                         :git_output (str (:out res) (:err res))
                         :git_stdout (:out res)
                         :git_stderr (:err res)}))))))

(defn- delete-recursively!
  [path]
  (let [f (some-> path io/file)]
    (when (and (some? f) (.exists f))
      (doseq [child (reverse (file-seq f))]
        (try
          (Files/deleteIfExists (.toPath child))
          (catch Exception _ nil))))))

(defn- git-remote-ref
  [remote]
  (let [repo-path (some-> (get remote "path") str/trim not-empty)
        branch (or (some-> (get remote "branch") str/trim not-empty) "main")
        rel-path-raw (or (some-> (get remote "bundle_path") str/trim not-empty) "vault.age")
        rel-path (try
                   (-> (Paths/get rel-path-raw (make-array String 0))
                       .normalize
                       .toString)
                   (catch Exception _
                     (throw (ex-info "remote bundle_path is invalid"
                                     {:reason reasons/reason-sync-failed}))))]
    (when (nil? repo-path)
      (throw (ex-info "remote path is empty" {:reason reasons/reason-missing-path})))
    (when (or (str/blank? rel-path)
              (= rel-path ".")
              (= rel-path ".."))
      (throw (ex-info "remote bundle_path is invalid"
                      {:reason reasons/reason-sync-failed})))
    (when (or (.isAbsolute (Paths/get rel-path (make-array String 0)))
              (str/starts-with? rel-path (str ".." java.io.File/separator))
              (str/starts-with? rel-path "../")
              (str/starts-with? rel-path "..\\"))
      (throw (ex-info "remote bundle_path must be relative"
                      {:reason reasons/reason-sync-failed})))
    {:repo-path repo-path
     :branch branch
     :rel-path rel-path
     :bundle-ref (format "%s@%s:%s" repo-path branch rel-path)}))

(defn- git-clone-no-checkout
  [repo-path]
  (let [repo-dir (.toString (Files/createTempDirectory "kimen-sync-git-" (make-array java.nio.file.attribute.FileAttribute 0)))
        cleanup (fn [] (delete-recursively! repo-dir))]
    (try
      (git-run! nil ["clone" "--quiet" "--no-checkout" repo-path repo-dir])
      {:repo-dir repo-dir
       :cleanup cleanup}
      (catch Exception e
        (cleanup)
        (throw e)))))

(defn- git-ref-exists?
  [repo-dir ref]
  (let [res (git-run-raw repo-dir ["rev-parse" "--verify" "--quiet" ref])]
    (cond
      (zero? (:exit res)) true
      (= 1 (:exit res)) false
      :else
      (let [msg (or (not-empty (str/trim (str (:err res))))
                    (not-empty (str/trim (str (:out res))))
                    "git ref check failed")]
        (throw (ex-info (format "git rev-parse --verify --quiet %s: %s" ref msg)
                        {:reason reasons/reason-sync-failed}))))))

(defn- git-checkout-remote-branch!
  [repo-dir branch]
  (let [ref (str "refs/remotes/origin/" branch)]
    (if-not (git-ref-exists? repo-dir ref)
      false
      (do
        (git-run! repo-dir ["checkout" "--quiet" "-B" branch ref])
        true))))

(defn- git-has-staged-changes?
  [repo-dir]
  (let [res (git-run-raw repo-dir ["diff" "--cached" "--quiet" "--exit-code"])]
    (cond
      (zero? (:exit res)) false
      (= 1 (:exit res)) true
      :else
      (let [msg (or (not-empty (str/trim (str (:err res))))
                    (not-empty (str/trim (str (:out res))))
                    "git diff --cached failed")]
        (throw (ex-info (format "git diff --cached --quiet --exit-code: %s" msg)
                        {:reason reasons/reason-sync-failed}))))))

(defn- git-push-rejected?
  [e]
  (let [out (some-> (ex-data e) (get :git_output) str str/lower-case)]
    (boolean (and (not (str/blank? out))
                  (or (str/includes? out "non-fast-forward")
                      (str/includes? out "[rejected]")
                      (str/includes? out "fetch first"))))))

(defn- git-push-bundle-file!
  [repo-path branch rel-path source-bundle-path]
  (let [{:keys [repo-dir cleanup]} (git-clone-no-checkout repo-path)]
    (try
      (when-not (git-checkout-remote-branch! repo-dir branch)
        (git-run! repo-dir ["checkout" "--quiet" "--orphan" branch]))
      (let [target-path (str (.getPath (io/file repo-dir rel-path)))]
        (.mkdirs (.getParentFile (io/file target-path)))
        (copy-file! source-bundle-path target-path)
        (git-run! repo-dir ["add" "--" rel-path])
        (if-not (git-has-staged-changes? repo-dir)
          (file-sha256-hex target-path)
          (do
            (git-run! repo-dir ["config" "user.name" "kimen"])
            (git-run! repo-dir ["config" "user.email" "kimen@localhost"])
            (git-run! repo-dir ["commit" "--quiet" "-m" (str "kimen sync push " (.toString (java.time.Instant/now)))])
            (try
              (git-run! repo-dir ["push" "--quiet" "origin" (format "HEAD:refs/heads/%s" branch)])
              (catch clojure.lang.ExceptionInfo e
                (if (git-push-rejected? e)
                  (throw (ex-info (.getMessage e) (assoc (ex-data e) :git_push_rejected true) e))
                  (throw e))))
            (file-sha256-hex target-path))))
      (finally
        (cleanup)))))

(defn- git-remote-revision
  [remote]
  (let [{:keys [repo-path branch rel-path bundle-ref]} (git-remote-ref remote)
        {:keys [repo-dir cleanup]} (git-clone-no-checkout repo-path)]
    (try
      (if-not (git-checkout-remote-branch! repo-dir branch)
        {:remote_rev nil
         :has_remote false
         :bundle_ref bundle-ref}
        (let [bundle-path (str (.getPath (io/file repo-dir rel-path)))
              [rev ok?] (file-revision bundle-path)]
          {:remote_rev rev
           :has_remote ok?
           :bundle_ref bundle-ref}))
      (finally
        (cleanup)))))

(defn- remote-revision
  [remote]
  (case (remote-type remote)
    "fs"
    (let [bundle-path (fs-remote-bundle-path remote)
          _ (when (str/blank? bundle-path)
              (throw (ex-info "remote bundle path is empty" {:reason reasons/reason-missing-path})))
          [rev ok?] (file-revision bundle-path)]
      {:remote_rev rev
       :has_remote ok?
       :bundle_ref bundle-path})

    "git"
    (git-remote-revision remote)

    (throw (ex-info (format "unsupported remote type %s" (pr-str (remote-type remote)))
                    {:reason reasons/reason-unsupported-remote-type}))))

(defn- materialize-remote-bundle-for-read
  [remote]
  (case (remote-type remote)
    "fs"
    (let [bundle-path (fs-remote-bundle-path remote)
          _ (when (str/blank? bundle-path)
              (throw (ex-info "remote bundle path is empty" {:reason reasons/reason-missing-path})))]
      {:bundle-path bundle-path
       :bundle-ref bundle-path
       :cleanup (fn [] nil)})

    "git"
    (let [{:keys [repo-path branch rel-path bundle-ref]} (git-remote-ref remote)
          {:keys [repo-dir cleanup]} (git-clone-no-checkout repo-path)]
      (if-not (git-checkout-remote-branch! repo-dir branch)
        (do
          (cleanup)
          (throw (ex-info "remote bundle is missing"
                          {:reason reasons/reason-remote-bundle-missing})))
        (let [bundle-path (str (.getPath (io/file repo-dir rel-path)))]
          (if-not (.exists (io/file bundle-path))
            (do
              (cleanup)
              (throw (ex-info "remote bundle is missing"
                              {:reason reasons/reason-remote-bundle-missing})))
            {:bundle-path bundle-path
             :bundle-ref bundle-ref
             :cleanup cleanup}))))

    (throw (ex-info (format "unsupported remote type %s" (pr-str (remote-type remote)))
                    {:reason reasons/reason-unsupported-remote-type}))))

(defn- seal-vault-to-git-remote!
  [vault-path recipient remote]
  (let [{:keys [repo-path branch rel-path bundle-ref]} (git-remote-ref remote)
        tmp-dir (.toString (Files/createTempDirectory "kimen-sync-git-seal-" (make-array java.nio.file.attribute.FileAttribute 0)))
        tmp-bundle (str (.getPath (io/file tmp-dir "vault.age")))]
    (try
      (bundle/seal-vault-file vault-path tmp-bundle [recipient])
      {:remote_rev (git-push-bundle-file! repo-path branch rel-path tmp-bundle)
       :bundle_ref bundle-ref}
      (finally
        (delete-recursively! tmp-dir)))))

(defn- sync-secret-hash
  [type value]
  (let [digest (MessageDigest/getInstance "SHA-256")
        type-bytes (.getBytes (str (or type "")) "UTF-8")
        value-bytes (.getBytes (str (or value "")) "UTF-8")]
    (.update digest type-bytes)
    (.update digest (byte-array [(byte 0)]))
    (.update digest value-bytes)
    (hex-bytes (.digest digest))))

(defn- normalize-hash-map
  [m]
  (when (map? m)
    (into {}
          (for [[k v] m
                :let [k (some-> k str str/trim)
                      v (some-> v str str/trim)]
                :when (and (not (str/blank? k))
                           (not (str/blank? v)))]
            [k v]))))

(defn- load-vault-snapshot
  [vault-path passphrase include-secrets?]
  (let [names (vault-v2/list-secret-names vault-path passphrase)]
    (reduce (fn [acc name]
              (let [sec (vault-v2/get-secret vault-path passphrase name)
                    h (sync-secret-hash (:type sec) (:value sec))
                    acc (assoc-in acc [:hashes name] h)]
                (if include-secrets?
                  (assoc-in acc [:secrets name] {:type (:type sec)
                                                 :value (:value sec)})
                  acc)))
            {:hashes {}
             :secrets (when include-secrets? {})}
            names)))

(defn- load-remote-vault-snapshot
  [remote passphrase include-secrets?]
  (let [{:keys [bundle-path cleanup]} (materialize-remote-bundle-for-read remote)
        identity-path (some-> (get remote "identity") str/trim not-empty)
        _ (when (nil? identity-path)
            (throw (ex-info "remote identity is not configured (set --identity on `remote add`)"
                            {:reason reasons/reason-remote-identity-missing})))
        identity (bundle/load-identity {:identity-file identity-path
                                        :from-stdin? false
                                        :stdin nil})
        tmp-path-obj (Files/createTempFile "kimen-sync-remote-" ".vault" (make-array java.nio.file.attribute.FileAttribute 0))
        tmp-path (.toString tmp-path-obj)]
    (try
      (bundle/open-to-vault-file bundle-path tmp-path identity true)
      (load-vault-snapshot tmp-path passphrase include-secrets?)
      (finally
        (cleanup)
        (try
          (Files/deleteIfExists tmp-path-obj)
          (catch Exception _ nil))))))

(def sync-change-unchanged :unchanged)
(def sync-change-added :added)
(def sync-change-removed :removed)
(def sync-change-modified :modified)

(defn- compute-sync-change-ops
  [baseline current]
  (let [keys* (->> (concat (keys baseline) (keys current))
                   set
                   sort)]
    (reduce (fn [acc k]
              (let [in-base (contains? baseline k)
                    in-cur (contains? current k)
                    base-hash (get baseline k)
                    cur-hash (get current k)
                    op (cond
                         (and (not in-base) in-cur) sync-change-added
                         (and in-base (not in-cur)) sync-change-removed
                         (and in-base in-cur (not= (str/trim (str base-hash))
                                                   (str/trim (str cur-hash))))
                         sync-change-modified
                         :else sync-change-unchanged)]
                (-> acc
                    (assoc-in [:ops k] op)
                    (cond-> (not= op sync-change-unchanged)
                      (update :changed conj k)))))
            {:ops {}
             :changed []}
            keys*)))

(defn- sync-conflict-change?
  [local-op remote-op local-hash remote-hash]
  (cond
    (and (= local-op sync-change-removed) (= remote-op sync-change-removed))
    false

    (and (or (= local-op sync-change-added) (= local-op sync-change-modified))
         (or (= remote-op sync-change-added) (= remote-op sync-change-modified)))
    (not= (str/trim (str (or local-hash "")))
          (str/trim (str (or remote-hash ""))))

    :else
    true))

(defn- analyze-sync-changes
  [baseline local remote]
  (if (nil? baseline)
    {:has-baseline false
     :local-ops {}
     :remote-ops {}
     :local-changed-keys []
     :remote-changed-keys []
     :overlapping-keys []
     :conflict-keys []
     :local-only-changed-keys []
     :remote-only-changed-keys []}
    (let [{local-ops :ops local-changed :changed} (compute-sync-change-ops baseline local)
          {remote-ops :ops remote-changed :changed} (compute-sync-change-ops baseline remote)
          keys* (->> (concat (keys local-ops) (keys remote-ops))
                     set
                     sort)
          merged (reduce (fn [acc k]
                           (let [lo (get local-ops k sync-change-unchanged)
                                 ro (get remote-ops k sync-change-unchanged)
                                 local? (not= lo sync-change-unchanged)
                                 remote? (not= ro sync-change-unchanged)]
                             (cond
                               (and local? remote?)
                               (-> acc
                                   (update :overlapping-keys conj k)
                                   (cond-> (sync-conflict-change? lo ro (get local k) (get remote k))
                                     (update :conflict-keys conj k)))

                               local?
                               (update acc :local-only-changed-keys conj k)

                               remote?
                               (update acc :remote-only-changed-keys conj k)

                               :else
                               acc)))
                         {:overlapping-keys []
                          :conflict-keys []
                          :local-only-changed-keys []
                          :remote-only-changed-keys []}
                         keys*)]
      {:has-baseline true
       :local-ops local-ops
       :remote-ops remote-ops
       :local-changed-keys local-changed
       :remote-changed-keys remote-changed
       :overlapping-keys (:overlapping-keys merged)
       :conflict-keys (:conflict-keys merged)
       :local-only-changed-keys (:local-only-changed-keys merged)
       :remote-only-changed-keys (:remote-only-changed-keys merged)})))

(defn- diff-current-snapshots
  [local remote]
  (let [only-local (->> (keys local)
                        (filter #(not (contains? remote %)))
                        sort
                        vec)
        only-remote (->> (keys remote)
                         (filter #(not (contains? local %)))
                         sort
                         vec)
        different (->> (keys local)
                       (filter #(and (contains? remote %)
                                     (not= (str/trim (str (get local %)))
                                           (str/trim (str (get remote %))))))
                       sort
                       vec)]
    {:only-local only-local
     :only-remote only-remote
     :different different}))

(defn- recommended-action-for-sync-analysis
  [analysis]
  (cond
    (not (:has-baseline analysis)) "sync_pull_or_sync_reset_baseline"
    (seq (:conflict-keys analysis)) "manual_reconcile"
    (and (seq (:remote-changed-keys analysis)) (empty? (:local-changed-keys analysis))) "sync_pull"
    (and (seq (:local-changed-keys analysis)) (empty? (:remote-changed-keys analysis))) "sync_push"
    (or (seq (:local-changed-keys analysis))
        (seq (:remote-changed-keys analysis))) "sync_pull_reconcile"
    :else "none"))

(defn- maybe-sync-passphrase
  [ctx opts]
  (let [env-pp (some-> (System/getenv passphrase/env-passphrase) str/trim not-empty)
        cmd-pp (some-> (:passphrase-cmd opts) str/trim not-empty)]
    (when (or env-pp cmd-pp (:passphrase-stdin? opts))
      (resolve-passphrase! ctx opts))))

(defn- resolve-sync-selected-keys!
  [raw-keys conflict-keys]
  (let [conflict-set (set conflict-keys)
        selected (->> raw-keys
                      (mapcat #(str/split (str (or % "")) #","))
                      (map str/trim)
                      (remove str/blank?)
                      distinct
                      sort
                      vec)]
    (if (empty? selected)
      (vec (sort conflict-keys))
      (let [invalid (->> selected
                         (remove conflict-set)
                         vec)]
        (when (seq invalid)
          (throw (ex-info (format "keys are not current conflict keys: %s" (str/join "," invalid))
                          {:reason reasons/reason-resolve-keys-not-conflicts})))
        selected))))

(defn- sync-state-active?
  [entry]
  (let [last-seen (some-> (get entry "last_seen_rev") str/trim not-empty)
        local-hash (some-> (get entry "local_hash") str/trim not-empty)
        baseline (normalize-hash-map (get entry "baseline_secret_hashes"))]
    (or (some? last-seen)
        (some? local-hash)
        (seq baseline))))

(defn- infer-sync-remote-from-state
  [config-path remotes]
  (let [candidates (->> remotes
                        (filter (fn [remote]
                                  (sync-state-active?
                                   (config/config-sync-entry config-path (get remote "name")))))
                        vec)]
    (when (= 1 (count candidates))
      (first candidates))))

(defn- select-sync-remote!
  [ctx remote-opt]
  (let [remotes (config/config-remote-list (:config-path ctx))
        from-sync (infer-sync-remote-from-state (:config-path ctx) remotes)
        requested-opt (some-> remote-opt str/trim not-empty)
        requested-env (some-> (getenv ctx env-remote-name) str/trim not-empty)
        requested (or requested-opt requested-env)]
    (when (empty? remotes)
      (throw (ex-info "no remotes configured" {:reason reasons/reason-no-remotes-configured})))
    (cond
      requested
      (or (some #(when (= requested (get % "name")) %) remotes)
          (throw (ex-info (format "remote %s not found" (pr-str requested))
                          {:reason (if (and (nil? requested-opt)
                                            (some? requested-env))
                                     reasons/reason-remote-not-found-from-env
                                     reasons/reason-remote-not-found)})))

      (some? from-sync)
      from-sync

      (= 1 (count remotes))
      (first remotes)

      :else
      (or (some #(when (= "origin" (get % "name")) %) remotes)
          (throw (ex-info "multiple remotes configured; choose --remote"
                          {:reason reasons/reason-multiple-remotes-configured}))))))

(def sync-preflight-check-doctor "doctor")
(def sync-preflight-check-status "sync_status")
(def sync-preflight-check-conflicts "sync_conflicts")
(def sync-preflight-check-pull-dry-run "sync_pull_dry_run")
(def sync-preflight-check-push-dry-run "sync_push_dry_run")

(def sync-preflight-check-order
  [sync-preflight-check-doctor
   sync-preflight-check-status
   sync-preflight-check-conflicts
   sync-preflight-check-pull-dry-run
   sync-preflight-check-push-dry-run])

(defn- normalize-sync-preflight-check-name!
  [raw]
  (let [token (some-> raw str/lower-case str/trim)]
    (case token
      "doctor" sync-preflight-check-doctor
      "status" sync-preflight-check-status
      "sync_status" sync-preflight-check-status
      "sync-status" sync-preflight-check-status
      "conflicts" sync-preflight-check-conflicts
      "sync_conflicts" sync-preflight-check-conflicts
      "sync-conflicts" sync-preflight-check-conflicts
      "pull" sync-preflight-check-pull-dry-run
      "sync_pull_dry_run" sync-preflight-check-pull-dry-run
      "sync-pull-dry-run" sync-preflight-check-pull-dry-run
      "push" sync-preflight-check-push-dry-run
      "sync_push_dry_run" sync-preflight-check-push-dry-run
      "sync-push-dry-run" sync-preflight-check-push-dry-run
      (throw (ex-info (format "unknown preflight check %s (available: doctor, status, conflicts, pull, push)"
                              (pr-str raw))
                      {:reason reasons/reason-unknown-preflight-check})))))

(defn- resolve-sync-preflight-checks!
  [only-checks skip-checks]
  (let [selected (if (empty? only-checks)
                   (set sync-preflight-check-order)
                   (->> only-checks
                        (map normalize-sync-preflight-check-name!)
                        set))
        selected (reduce (fn [acc raw]
                           (disj acc (normalize-sync-preflight-check-name! raw)))
                         selected
                         skip-checks)
        ordered (->> sync-preflight-check-order
                     (filter selected)
                     vec)]
    (when (empty? ordered)
      (throw (ex-info "no preflight checks selected (available: doctor, sync_status, sync_conflicts, sync_pull_dry_run, sync_push_dry_run)"
                      {:reason reasons/reason-no-preflight-checks-selected})))
    ordered))

(defn- build-sync-preflight-check-args
  [check-name opts]
  (let [remote (some-> (:remote opts) str/trim not-empty)
        stale-threshold (some-> (:stale-threshold opts) str/trim not-empty)]
    (case check-name
      "doctor"
      (cond-> ["doctor" "--json"]
        (some? (:profile opts)) (into ["--profile" (:profile opts)])
        (some? (:bundle-in opts)) (into ["--bundle-in" (:bundle-in opts)])
        (some? (:identity opts)) (into ["--identity" (:identity opts)])
        (:strict? opts) (conj "--strict")
        (:allow-missing-vault? opts) (conj "--allow-missing-vault"))

      "sync_status"
      (cond-> ["sync" "status"]
        (some? remote) (into ["--remote" remote])
        (some? stale-threshold) (into ["--stale-threshold" stale-threshold])
        (:strict? opts) (conj "--strict")
        true (conj "--json"))

      "sync_conflicts"
      (cond-> ["sync" "conflicts"]
        (some? remote) (into ["--remote" remote])
        (some? stale-threshold) (into ["--stale-threshold" stale-threshold])
        (:strict? opts) (conj "--strict")
        true (conj "--json"))

      "sync_pull_dry_run"
      (cond-> ["sync" "pull"]
        (some? remote) (into ["--remote" remote])
        true (into ["--dry-run" "--json"]))

      "sync_push_dry_run"
      (cond-> ["sync" "push"]
        (some? remote) (into ["--remote" remote])
        true (into ["--dry-run" "--json"]))

      (throw (ex-info (format "unsupported preflight check %s" (pr-str check-name))
                      {:reason reasons/reason-unsupported-preflight-check})))))

(defn- sync-status-payload
  ([ctx remote]
   (sync-status-payload ctx remote {}))
  ([ctx remote {:keys [stale-threshold-ms]}]
   (let [remote-name (get remote "name")
         remote-type (remote-type remote)
         remote-path (some-> (get remote "path") str/trim)
         {:keys [remote_rev has_remote bundle_ref]} (remote-revision remote)
         lock-path (when (remote-supports-push-lock? remote)
                     (some-> bundle_ref (str ".lock")))
         lock-file (when lock-path (io/file lock-path))
         has-lock (boolean (and lock-file (.exists lock-file)))
         lock-age-ms (when has-lock
                       (max 0 (- (System/currentTimeMillis) (.lastModified lock-file))))
         lock-age-seconds (if lock-age-ms
                            (quot lock-age-ms 1000)
                            0)
         stale-threshold-ms (long (or stale-threshold-ms 0))
         likely-stale (boolean (and has-lock
                                    (pos? stale-threshold-ms)
                                    (>= lock-age-ms stale-threshold-ms)))
         vault-path (vault-path/resolve-vault-path ctx nil)
         has-local (boolean (.exists (io/file vault-path)))
         recipient (some-> (get remote "recipient") str/trim not-empty)
         identity (some-> (get remote "identity") str/trim not-empty)
         sync-entry (config/config-sync-entry (:config-path ctx) remote-name)
         last-seen (some-> sync-entry (get "last_seen_rev") str/trim not-empty)
         local-hash (when has-local (file-sha256-hex vault-path))
         last-local-hash (some-> sync-entry (get "local_hash") str/trim not-empty)
         local-changed (boolean (and has-local last-local-hash local-hash (not= last-local-hash local-hash)))
         missing-recipient? (nil? recipient)
         missing-identity? (nil? identity)
         conflict (detect-sync-conflict last-seen remote_rev has_remote)
         has-conflict (boolean (:has-conflict conflict))
         conflict-reason (some-> (:reason conflict) str/trim not-empty)
         remote-changed (= conflict-reason reasons/reason-remote-changed)
         needs-pull (boolean (and has_remote
                                  (or (nil? last-seen)
                                      (not= last-seen remote_rev))))
         can-push-base (cond
                         (and (not has_remote) (some? last-seen)) false
                         (and has_remote (nil? last-seen)) false
                         (and has_remote (not= last-seen remote_rev)) false
                         :else true)
         can-push (boolean (and can-push-base
                                (not has-lock)
                                has-local
                                (not missing-recipient?)))
         blockers (cond-> []
                    (not has-local) (conj reasons/reason-local-vault-missing)
                    has-lock (conj reasons/reason-remote-lock-present)
                    missing-recipient? (conj reasons/reason-remote-recipient-missing)
                    (and needs-pull missing-identity?) (conj reasons/reason-remote-identity-missing)
                    has-conflict (conj conflict-reason))
         in-sync (boolean (and has_remote has-local (not needs-pull) (not local-changed)))
         recommended-action
         (cond
           (and needs-pull missing-identity?) "configure_remote_identity"
           (and (not has-local) (not has_remote)) "vault_init"
           (and (not has-local) has_remote) "sync_pull"
           (and missing-recipient? (not needs-pull)) "configure_remote_recipient"
           (and has-conflict (= conflict-reason reasons/reason-remote-disappeared)) "sync_reset_baseline_or_remote_recreate"
           (or needs-pull
               (and has-conflict (not= conflict-reason reasons/reason-remote-disappeared))) "sync_pull"
           has-lock "wait_or_sync_unlock"
           can-push "sync_push"
           :else "none")]
     {:ok true
      :action "sync_status"
      :exit_code 0
      :remote remote-name
      :remote_type remote-type
      :remote_path remote-path
      :bundle_path bundle_ref
      :vault_path vault-path
      :remote_rev remote_rev
      :last_seen_rev last-seen
      :local_hash local-hash
      :last_local_hash last-local-hash
      :has_remote has_remote
      :has_lock has-lock
      :has_local has-local
      :in_sync in-sync
      :remote_changed remote-changed
      :local_changed local-changed
      :has_conflict has-conflict
      :can_push can-push
      :needs_pull needs-pull
      :lock_path lock-path
      :lock_age (when has-lock (str lock-age-seconds "s"))
      :lock_pid nil
      :lock_host nil
      :lock_user nil
      :lock_created nil
      :lock_error nil
      :lock_blocks_push has-lock
      :likely_stale likely-stale
      :lock_age_seconds lock-age-seconds
      :reason conflict-reason
      :expected_rev (:expected-rev conflict)
      :actual_rev (:actual-rev conflict)
      :message (:message conflict)
      :blockers blockers
      :recommended_action recommended-action})))

(defn- sync-status-strict-error
  [payload]
  (let [blockers (vec (:blockers payload))
        conflict-reason (some-> (:reason payload) str/trim not-empty)
        conflict-data (cond-> {:reason (or conflict-reason reasons/reason-sync-failed)}
                        (some? (:expected_rev payload)) (assoc :expected_rev (:expected_rev payload))
                        (some? (:actual_rev payload)) (assoc :actual_rev (:actual_rev payload))
                        (some? (:recommended_action payload)) (assoc :recommended_action (:recommended_action payload)))
        push-reason (or (first blockers) reasons/reason-push-blocked)]
    (cond
      (:has_conflict payload)
      (ex-info (or (:message payload) "sync status indicates conflict")
               conflict-data)

      (:has_lock payload)
      (ex-info "remote lock present"
               {:reason reasons/reason-remote-lock-present
                :recommended_action "wait_or_sync_unlock"})

      (not (:has_local payload))
      (ex-info "local vault missing"
               {:reason reasons/reason-local-vault-missing})

      (some #{reasons/reason-remote-recipient-missing} blockers)
      (ex-info "remote recipient is not configured (set --recipient on `remote add`)"
               {:reason reasons/reason-remote-recipient-missing})

      (some #{reasons/reason-remote-identity-missing} blockers)
      (ex-info "remote identity is not configured (set --identity on `remote add`)"
               {:reason reasons/reason-remote-identity-missing})

      (not (:can_push payload))
      (ex-info (format "sync status indicates push is blocked (blockers=%s)"
                       (if (seq blockers) (str/join "," blockers) "none"))
               {:reason push-reason
                :recommended_action (:recommended_action payload)})

      :else
      nil)))

(defn- sync-status-terse-line
  [payload]
  (format "remote=%s in_sync=%s can_push=%s needs_pull=%s has_lock=%s blockers=%s recommended_action=%s\n"
          (:remote payload)
          (:in_sync payload)
          (:can_push payload)
          (:needs_pull payload)
          (:has_lock payload)
          (if (seq (:blockers payload))
            (str/join "," (:blockers payload))
            "-")
          (:recommended_action payload)))

(defn- sync-status-lock-lines
  [payload]
  (if (:has_lock payload)
    (cond-> [(format "push-lock: present (%s)" (:lock_path payload))]
      (:lock_age payload) (conj (str "push-lock-age: " (:lock_age payload)))
      (:lock_pid payload) (conj (str "push-lock-pid: " (:lock_pid payload)))
      (:lock_host payload) (conj (str "push-lock-host: " (:lock_host payload)))
      (:lock_user payload) (conj (str "push-lock-user: " (:lock_user payload)))
      (:lock_created payload) (conj (str "push-lock-created: " (:lock_created payload)))
      (:lock_error payload) (conj (str "push-lock-error: " (:lock_error payload))))
    ["push-lock: (none)"]))

(defn- sync-status-plain-lines
  [payload stale-threshold-ms]
  (cond-> [(format "remote: %s (%s)" (:remote payload) (:remote_type payload))
           (str "path: " (:remote_path payload))
           (str "remote-rev: " (or (:remote_rev payload) "(missing)"))
           (str "last-seen-rev: " (or (:last_seen_rev payload) "(none)"))
           (str "in-sync: " (:in_sync payload))
           (str "can-push: " (:can_push payload))
           (str "needs-pull: " (:needs_pull payload))
           (str "lock-blocks-push: " (:lock_blocks_push payload))
           (str "blockers: " (if (seq (:blockers payload))
                               (str/join "," (:blockers payload))
                               "(none)"))
           (str "recommended-action: " (:recommended_action payload))]
    (and (:has_lock payload) (pos? stale-threshold-ms))
    (conj (str "likely-stale: " (:likely_stale payload)
               " (threshold=" stale-threshold-ms "ms)"))

    true
    (into (sync-status-lock-lines payload))

    true
    (conj "")))

(defn- handle-sync-status
  [ctx args]
  (let [[opts parse-error] (parse-sync-status-opts args)
        json? (:json? opts)]
    (if parse-error
      (sync-error-result json? (ex-info parse-error {:reason reasons/reason-sync-failed}))
      (try
        (when (neg? (:stale-threshold-ms opts))
          (throw (ex-info "--stale-threshold must be >= 0"
                          {:reason reasons/reason-invalid-stale-threshold})))
        (let [remote (select-sync-remote! ctx (:remote opts))
              payload (sync-status-payload ctx remote opts)]
          (when-let [e (when (:strict? opts)
                         (sync-status-strict-error payload))]
            (throw e))
          (if json?
            (success-json payload)
            (if (:terse? opts)
              (result {:exit-code 0
                       :stdout (sync-status-terse-line payload)})
              (result {:exit-code 0
                       :stdout (str/join
                                "\n"
                                (sync-status-plain-lines payload (:stale-threshold-ms opts)))}))))
        (catch Exception e
          (sync-error-result json? e))))))

(defn- sync-conflicts-strict-error
  [payload]
  (cond
    (:has_conflict payload)
    (ex-info (or (:message payload) "sync conflicts indicates conflict")
             (cond-> {:reason (or (:reason payload) reasons/reason-sync-failed)}
               (some? (:expected_rev payload)) (assoc :expected_rev (:expected_rev payload))
               (some? (:actual_rev payload)) (assoc :actual_rev (:actual_rev payload))
               (some? (:recommended_action payload)) (assoc :recommended_action (:recommended_action payload))))

    (:has_lock payload)
    (ex-info "remote lock present"
             {:reason reasons/reason-remote-lock-present
              :recommended_action "wait_or_sync_unlock"})

    :else
    nil))

(defn- handle-sync-conflicts
  [ctx args]
  (let [[opts parse-error] (parse-sync-status-opts args)
        json? (:json? opts)]
    (if parse-error
      (sync-error-result json? (ex-info parse-error {:reason reasons/reason-sync-failed}))
      (try
        (when (neg? (:stale-threshold-ms opts))
          (throw (ex-info "--stale-threshold must be >= 0"
                          {:reason reasons/reason-invalid-stale-threshold})))
        (let [remote (select-sync-remote! ctx (:remote opts))
              status (sync-status-payload ctx remote opts)
              conflict-reason (some-> (:reason status) str/trim not-empty)
              blockers (cond-> []
                         (:has_lock status) (conj reasons/reason-remote-lock-present)
                         (:has_conflict status) (conj conflict-reason))
              recommended-action (cond
                                   (and (:has_conflict status)
                                        (= conflict-reason reasons/reason-remote-disappeared))
                                   "sync_reset_baseline_or_remote_recreate"

                                   (:has_conflict status)
                                   "sync_pull"

                                   (:has_lock status)
                                   "wait_or_sync_unlock"

                                   :else
                                   "none")
              payload {:ok true
                       :action "sync_conflicts"
                       :exit_code 0
                       :remote (:remote status)
                       :remote_type (:remote_type status)
                       :remote_path (:remote_path status)
                       :bundle_path (:bundle_path status)
                       :remote_rev (:remote_rev status)
                       :last_seen_rev (:last_seen_rev status)
                       :has_remote (:has_remote status)
                       :has_local (:has_local status)
                       :lock_path (:lock_path status)
                       :lock_age (:lock_age status)
                       :lock_pid (:lock_pid status)
                       :lock_host (:lock_host status)
                       :lock_user (:lock_user status)
                       :lock_created (:lock_created status)
                       :lock_error (:lock_error status)
                       :local_hash (:local_hash status)
                       :last_local_hash (:last_local_hash status)
                       :has_lock (:has_lock status)
                       :reason conflict-reason
                       :expected_rev (:expected_rev status)
                       :actual_rev (:actual_rev status)
                       :message (:message status)
                       :remote_changed (:remote_changed status)
                       :local_changed (:local_changed status)
                       :has_conflict (:has_conflict status)
                       :lock_blocks_push (:lock_blocks_push status)
                       :likely_stale (:likely_stale status)
                       :lock_age_seconds (:lock_age_seconds status)
                       :blockers blockers
                       :recommended_action recommended-action}]
          (when-let [e (when (:strict? opts)
                         (sync-conflicts-strict-error payload))]
            (throw e))
          (if json?
            (success-json payload)
            (if (:terse? opts)
              (result {:exit-code 0
                       :stdout (format "remote=%s has_conflict=%s reason=%s has_lock=%s blockers=%s recommended_action=%s\n"
                                       (:remote payload)
                                       (:has_conflict payload)
                                       (or (:reason payload) "none")
                                       (:has_lock payload)
                                       (if (seq (:blockers payload))
                                         (str/join "," (:blockers payload))
                                         "-")
                                       (:recommended_action payload))})
              (result {:exit-code 0
                       :stdout (str/join
                                "\n"
                                [(str "remote: " (:remote payload))
                                 (str "has-conflict: " (:has_conflict payload))
                                 (str "remote-changed: " (:remote_changed payload))
                                 (str "local-changed: " (:local_changed payload))
                                 (str "has-lock: " (:has_lock payload))
                                 (str "recommended-action: " (:recommended_action payload))
                                 ""])}))))
        (catch Exception e
          (sync-error-result json? e))))))

(defn- sync-changes-payload
  [ctx remote opts]
  (let [remote-name (get remote "name")
        {:keys [remote_rev has_remote]} (remote-revision remote)
        vault-path (vault-path/resolve-vault-path ctx nil)
        has-local (boolean (.exists (io/file vault-path)))
        sync-entry (config/config-sync-entry (:config-path ctx) remote-name)
        baseline (normalize-hash-map (get sync-entry "baseline_secret_hashes"))
        passphrase (when (or has-local has_remote)
                     (resolve-passphrase! ctx opts))
        local-snap (if has-local
                     (load-vault-snapshot vault-path passphrase false)
                     {:hashes {}})
        remote-snap (if has_remote
                      (load-remote-vault-snapshot remote passphrase false)
                      {:hashes {}})
        analysis (analyze-sync-changes baseline (:hashes local-snap) (:hashes remote-snap))
        current-diff (diff-current-snapshots (:hashes local-snap) (:hashes remote-snap))
        can-reconcile (boolean (and (:has-baseline analysis)
                                    (empty? (:conflict-keys analysis))))
        recommended-action (recommended-action-for-sync-analysis analysis)]
    {:ok true
     :action "sync_changes"
     :exit_code 0
     :remote remote-name
     :has_baseline (:has-baseline analysis)
     :baseline_rev (some-> sync-entry (get "last_seen_rev") str/trim not-empty)
     :remote_rev remote_rev
     :has_remote has_remote
     :has_local has-local
     :local_changed_keys (:local-changed-keys analysis)
     :remote_changed_keys (:remote-changed-keys analysis)
     :overlapping_keys (:overlapping-keys analysis)
     :conflict_keys (:conflict-keys analysis)
     :local_only_changed_keys (:local-only-changed-keys analysis)
     :remote_only_changed_keys (:remote-only-changed-keys analysis)
     :current_only_local_keys (:only-local current-diff)
     :current_only_remote_keys (:only-remote current-diff)
     :current_different_keys (:different current-diff)
     :can_reconcile can-reconcile
     :recommended_action recommended-action}))

(defn- handle-sync-changes
  [ctx args]
  (let [[opts parse-error] (parse-sync-changes-opts args)
        json? (:json? opts)]
    (if parse-error
      (sync-error-result json? (ex-info parse-error {:reason reasons/reason-sync-failed}))
      (try
        (let [remote (select-sync-remote! ctx (:remote opts))
              payload (sync-changes-payload ctx remote opts)]
          (cond
            json?
            (success-json payload)

            (:terse? opts)
            (result {:exit-code 0
                     :stdout (format "remote=%s has_baseline=%s can_reconcile=%s local_changed=%d remote_changed=%d overlapping=%d conflicts=%d recommended_action=%s\n"
                                     (:remote payload)
                                     (:has_baseline payload)
                                     (:can_reconcile payload)
                                     (count (:local_changed_keys payload))
                                     (count (:remote_changed_keys payload))
                                     (count (:overlapping_keys payload))
                                     (count (:conflict_keys payload))
                                     (:recommended_action payload))})

            :else
            (result {:exit-code 0
                     :stdout (str/join
                              "\n"
                              [(str "remote: " (:remote payload))
                               (str "has-baseline: " (:has_baseline payload))
                               (str "can-reconcile: " (:can_reconcile payload))
                               (str "local-changed-keys: " (count (:local_changed_keys payload)))
                               (str "remote-changed-keys: " (count (:remote_changed_keys payload)))
                               (str "overlapping-keys: " (count (:overlapping_keys payload)))
                               (str "conflict-keys: " (count (:conflict_keys payload)))
                               (when (seq (:conflict_keys payload))
                                 (str "conflict-list: " (str/join "," (:conflict_keys payload))))
                               (str "recommended-action: " (:recommended_action payload))
                               ""])})))
        (catch Exception e
          (sync-error-result json? e))))))

(defn- apply-sync-resolve-remote!
  [vault-path passphrase selected-keys remote-secrets]
  (doseq [k selected-keys]
    (if-let [sec (get remote-secrets k)]
      (vault-v2/set-secret! vault-path passphrase k (:value sec))
      (try
        (vault-v2/delete-secret! vault-path passphrase k)
        (catch clojure.lang.ExceptionInfo e
          (when-not (= reasons/reason-secret-not-found (:reason (ex-data e)))
            (throw e)))))))

(defn- delete-secret-if-present!
  [vault-path passphrase key]
  (try
    (vault-v2/delete-secret! vault-path passphrase key)
    (catch clojure.lang.ExceptionInfo e
      (when-not (= reasons/reason-secret-not-found (:reason (ex-data e)))
        (throw e)))))

(defn- apply-sync-reconcile-merge!
  [vault-path passphrase local-snap remote-snap analysis]
  (let [keys* (->> (concat (keys (:local-ops analysis))
                           (keys (:remote-ops analysis)))
                   set
                   sort)]
    (doseq [k keys*]
      (let [local-op (get (:local-ops analysis) k sync-change-unchanged)
            remote-op (get (:remote-ops analysis) k sync-change-unchanged)]
        (cond
          (and (= local-op sync-change-unchanged) (= remote-op sync-change-unchanged))
          nil

          (and (not= local-op sync-change-unchanged) (= remote-op sync-change-unchanged))
          nil

          (and (= local-op sync-change-unchanged) (not= remote-op sync-change-unchanged))
          (if (= remote-op sync-change-removed)
            (delete-secret-if-present! vault-path passphrase k)
            (if-let [remote-sec (get-in remote-snap [:secrets k])]
              (vault-v2/set-secret! vault-path passphrase k (:value remote-sec))
              (throw (ex-info (format "remote secret %s missing during reconcile" (pr-str k))
                              {:reason reasons/reason-sync-failed}))))

          :else
          (cond
            (and (= local-op sync-change-removed) (= remote-op sync-change-removed))
            (delete-secret-if-present! vault-path passphrase k)

            (sync-conflict-change? local-op remote-op
                                   (get-in local-snap [:hashes k])
                                   (get-in remote-snap [:hashes k]))
            (throw (ex-info "local and remote have overlapping changes; manual reconciliation required"
                            {:reason reasons/reason-overlapping-changes
                             :recommended_action "manual_reconcile"}))

            :else
            nil))))))

(defn- handle-sync-resolve
  [ctx args]
  (let [[opts parse-error] (parse-sync-resolve-opts args)
        json? (:json? opts)
        take (some-> (:take opts) str/lower-case str/trim)]
    (cond
      parse-error
      (sync-error-result json? (ex-info parse-error {:reason reasons/reason-sync-failed}))

      (not (#{"local" "remote"} take))
      (sync-error-result json? (ex-info "--take must be one of: local, remote"
                                        {:reason reasons/reason-invalid-take}))

      :else
      (try
        (let [remote (select-sync-remote! ctx (:remote opts))
              remote-name (get remote "name")
              {:keys [remote_rev has_remote]} (remote-revision remote)
              _ (when-not has_remote
                  (throw (ex-info "remote bundle is missing"
                                  {:reason reasons/reason-remote-missing
                                   :recommended_action "sync_reset_baseline_or_remote_recreate"})))
              vault-path (vault-path/resolve-vault-path ctx nil)
              _ (when-not (.exists (io/file vault-path))
                  (throw (ex-info (format "local vault missing: %s" vault-path)
                                  {:reason reasons/reason-local-vault-missing})))
              passphrase (resolve-passphrase! ctx opts)
              local-snap (load-vault-snapshot vault-path passphrase false)
              remote-snap (load-remote-vault-snapshot remote passphrase (= take "remote"))
              sync-entry (config/config-sync-entry (:config-path ctx) remote-name)
              baseline (normalize-hash-map (get sync-entry "baseline_secret_hashes"))
              _ (when (nil? baseline)
                  (throw (ex-info "cannot resolve conflicts without baseline key hashes; run `kimen sync pull` first"
                                  {:reason reasons/reason-reconcile-baseline-missing
                                   :recommended_action "sync_pull"})))
              analysis (analyze-sync-changes baseline (:hashes local-snap) (:hashes remote-snap))
              _ (when (empty? (:conflict-keys analysis))
                  (throw (ex-info "no overlapping key conflicts to resolve"
                                  {:reason reasons/reason-no-overlapping-conflicts})))
              selected-keys (resolve-sync-selected-keys! (:keys opts) (:conflict-keys analysis))
              _ (when (= take "remote")
                  (apply-sync-resolve-remote! vault-path passphrase selected-keys (:secrets remote-snap)))
              _ (when (and (= take "remote")
                           (not (.exists (io/file vault-path))))
                  (throw (ex-info "local vault missing after sync resolve"
                                  {:reason reasons/reason-local-vault-missing-after-resolve})))
              local-resolved (if (= take "remote")
                               (load-vault-snapshot vault-path passphrase false)
                               local-snap)
              baseline-resolved (reduce (fn [acc k]
                                          (if-let [remote-hash (get-in remote-snap [:hashes k])]
                                            (assoc acc k remote-hash)
                                            (dissoc acc k)))
                                        baseline
                                        selected-keys)
              remaining (analyze-sync-changes baseline-resolved
                                              (:hashes local-resolved)
                                              (:hashes remote-snap))
              _ (when-not (.exists (io/file vault-path))
                  (throw (ex-info "local vault missing after sync resolve"
                                  {:reason reasons/reason-local-vault-missing-after-resolve})))
              local-hash (file-sha256-hex vault-path)
              next-entry (cond-> (merge (or sync-entry {})
                                        {"local_hash" local-hash
                                         "baseline_secret_hashes" baseline-resolved})
                           (empty? baseline-resolved) (dissoc "baseline_secret_hashes")
                           (empty? (:remote-changed-keys remaining)) (assoc "last_seen_rev" remote_rev))
              saved-entry (config/config-sync-replace! (:config-path ctx) remote-name next-entry)
              last-seen (some-> saved-entry (get "last_seen_rev") str/trim not-empty)
              payload {:ok true
                       :action "sync_resolve"
                       :exit_code 0
                       :remote remote-name
                       :remote_rev remote_rev
                       :last_seen_rev last-seen
                       :take take
                       :keys selected-keys
                       :resolved_count (count selected-keys)
                       :remaining_conflict_keys (:conflict-keys remaining)
                       :remaining_conflict_count (count (:conflict-keys remaining))
                       :recommended_action (recommended-action-for-sync-analysis remaining)}]
          (if json?
            (success-json payload)
            (result {:exit-code 0
                     :stdout (str/join
                              "\n"
                              [(str "remote: " (:remote payload))
                               (str "take: " (:take payload))
                               (str "resolved-keys: " (:resolved_count payload))
                               (str "remaining-conflicts: " (:remaining_conflict_count payload))
                               (str "recommended-action: " (:recommended_action payload))
                               ""])})))
        (catch Exception e
          (sync-error-result json? e))))))

(defn- handle-sync-push
  [ctx args]
  (let [[opts parse-error] (parse-sync-transfer-opts args)
        json? (:json? opts)]
    (cond
      parse-error
      (sync-error-result json? (ex-info parse-error {:reason reasons/reason-sync-failed}))

      (:reconcile? opts)
      (sync-error-result json?
                         (ex-info "--reconcile is only valid for sync pull"
                                  {:reason reasons/reason-sync-failed}))

      (:no-backup? opts)
      (sync-error-result json?
                         (ex-info "--no-backup is only valid for sync pull"
                                  {:reason reasons/reason-sync-failed}))

      (neg? (:lock-wait-ms opts))
      (sync-error-result json?
                         (ex-info "--lock-wait must be >= 0"
                                  {:reason reasons/reason-invalid-lock-wait}))

      (neg? (:break-stale-lock-after-ms opts))
      (sync-error-result json?
                         (ex-info "--break-stale-lock-after must be >= 0"
                                  {:reason reasons/reason-invalid-break-stale-lock-after}))

      (and (:dry-run? opts)
           (or (pos? (:lock-wait-ms opts))
               (pos? (:break-stale-lock-after-ms opts))))
      (sync-error-result json?
                         (ex-info "--dry-run cannot be combined with --lock-wait/--break-stale-lock-after"
                                  {:reason reasons/reason-conflicting-dry-run-lock-flags}))

      :else
      (try
        (let [remote (select-sync-remote! ctx (:remote opts))
              remote-name (get remote "name")
              remote-type (remote-type remote)
              recipient (some-> (get remote "recipient") str/trim not-empty)
              vault-path (vault-path/resolve-vault-path ctx nil)
              has-local (boolean (.exists (io/file vault-path)))
              sync-entry (config/config-sync-entry (:config-path ctx) remote-name)
              last-seen (some-> sync-entry (get "last_seen_rev") str/trim not-empty)
              {:keys [remote_rev has_remote bundle_ref]} (remote-revision remote)
              remote-exists? has_remote
              remote-rev-before remote_rev
              force? (boolean (:force? opts))
              lock-supported? (remote-supports-push-lock? remote)
              _ (when (and (not lock-supported?)
                           (or (pos? (:lock-wait-ms opts))
                               (pos? (:break-stale-lock-after-ms opts))))
                  (throw (ex-info "--lock-wait/--break-stale-lock-after are only supported for fs remotes"
                                  {:reason reasons/reason-lock-flags-require-fs-remote})))
              lock-path (when lock-supported? (remote-lock-path remote))
              _ (when (and lock-supported? (str/blank? lock-path))
                  (throw (ex-info "remote lock path is empty" {:reason reasons/reason-missing-path})))
              lock-state (if lock-supported?
                           (await-push-lock-clear! lock-path
                                                   (:lock-wait-ms opts)
                                                   (:break-stale-lock-after-ms opts))
                           {:locked? false
                            :stale_lock_broken false})
              stale-lock-broken? (boolean (:stale_lock_broken lock-state))
              _ (when (:locked? lock-state)
                  (throw (ex-info "remote lock present" {:reason reasons/reason-remote-lock-present})))
              _ (when-not has-local
                  (throw (ex-info (format "local vault missing: %s" vault-path)
                                  {:reason reasons/reason-local-vault-missing})))
              _ (when (nil? recipient)
                  (throw (ex-info "remote recipient is not configured (set --recipient on `remote add`)"
                                  {:reason reasons/reason-remote-recipient-missing})))
              _ (when (and (not force?) (not remote-exists?) (some? last-seen))
                  (throw (ex-info (format "remote bundle disappeared since last sync (expected rev %s)" last-seen)
                                  {:reason reasons/reason-remote-disappeared
                                   :expected_rev last-seen
                                   :recommended_action "sync_reset_baseline_or_remote_recreate"})))
              _ (when (and (not force?) remote-exists? (nil? last-seen))
                  (throw (ex-info "remote has data but no local baseline; run sync pull first"
                                  {:reason reasons/reason-no-local-baseline
                                   :actual_rev remote-rev-before
                                   :recommended_action "sync_pull"})))
              _ (when (and (not force?) remote-exists? last-seen remote-rev-before (not= last-seen remote-rev-before))
                  (throw (ex-info "remote changed since last baseline; run sync pull"
                                  {:reason reasons/reason-remote-changed
                                   :expected_rev last-seen
                                   :actual_rev remote-rev-before
                                   :recommended_action "sync_pull"})))]
          (if (:dry-run? opts)
            (let [payload {:ok true
                           :action "sync_push_dry_run"
                           :exit_code 0
                           :remote remote-name
                           :remote_type remote-type
                           :remote_path (get remote "path")
                           :bundle_path bundle_ref
                           :vault_path vault-path
                           :remote_rev remote-rev-before
                           :last_seen_rev last-seen
                           :dry_run true
                           :forced force?
                           :stale_lock_broken stale-lock-broken?
                           :has_remote remote-exists?
                           :has_local has-local
                           :can_push true}]
              (if json?
                (success-json payload)
                (result {:exit-code 0
                         :stdout (format "dry-run: would push %s -> %s\n" vault-path bundle_ref)})))
            (let [{remote-rev :remote_rev
                   bundle-ref :bundle_ref}
                  (case remote-type
                    "fs"
                    (do
                      (bundle/seal-vault-file vault-path bundle_ref [recipient])
                      {:remote_rev (file-sha256-hex bundle_ref)
                       :bundle_ref bundle_ref})

                    "git"
                    (try
                      (seal-vault-to-git-remote! vault-path recipient remote)
                      (catch clojure.lang.ExceptionInfo e
                        (if (true? (get (ex-data e) :git_push_rejected))
                          (let [{current-rev :remote_rev
                                 current-has-remote :has_remote}
                                (remote-revision remote)
                                conflict (detect-sync-conflict remote-rev-before current-rev current-has-remote)
                                reason (:reason conflict)]
                            (if (:has-conflict conflict)
                              (throw (ex-info (or (:message conflict) (.getMessage e))
                                              (cond-> {:reason reason}
                                                (some? (:expected-rev conflict)) (assoc :expected_rev (:expected-rev conflict))
                                                (some? (:actual-rev conflict)) (assoc :actual_rev (:actual-rev conflict))
                                                (some? (sync-state/recommended-action-for-reason reason))
                                                (assoc :recommended_action (sync-state/recommended-action-for-reason reason)))
                                              e))
                              (throw e)))
                          (throw e))))

                    (throw (ex-info (format "unsupported remote type %s" (pr-str remote-type))
                                    {:reason reasons/reason-unsupported-remote-type})))
                  _ (when-not (.exists (io/file vault-path))
                      (throw (ex-info "local vault disappeared before baseline update"
                                      {:reason reasons/reason-local-vault-disappeared-before-baseline-update})))
                  local-hash (file-sha256-hex vault-path)
                  sync-passphrase (maybe-sync-passphrase ctx opts)
                  baseline-secret-hashes (when sync-passphrase
                                           (:hashes (load-vault-snapshot vault-path sync-passphrase false)))
                  _ (config/config-sync-mark-seen! (:config-path ctx) remote-name remote-rev local-hash baseline-secret-hashes)
                  payload {:ok true
                           :action "sync_push"
                           :exit_code 0
                           :remote remote-name
                           :remote_type remote-type
                           :remote_path (get remote "path")
                           :bundle_path bundle-ref
                           :vault_path vault-path
                           :remote_rev remote-rev
                           :last_seen_rev remote-rev
                           :local_hash local-hash
                           :has_baseline_hashes (boolean baseline-secret-hashes)
                           :forced force?
                           :stale_lock_broken stale-lock-broken?
                           :has_local true
                           :can_push true}]
              (if json?
                (success-json payload)
                (result {:exit-code 0
                         :stdout (format "ok (pushed %s)\n" remote-name)})))))
        (catch Exception e
          (sync-error-result json? e))))))

(defn- handle-sync-pull
  [ctx args]
  (let [[opts parse-error] (parse-sync-transfer-opts args)
        json? (:json? opts)]
    (cond
      parse-error
      (sync-error-result json? (ex-info parse-error {:reason reasons/reason-sync-failed}))

      (:force? opts)
      (sync-error-result json?
                         (ex-info "--force is only valid for sync push"
                                  {:reason reasons/reason-sync-failed}))

      (or (pos? (:lock-wait-ms opts))
          (pos? (:break-stale-lock-after-ms opts)))
      (sync-error-result json?
                         (ex-info "--lock-wait/--break-stale-lock-after are only supported for sync push"
                                  {:reason reasons/reason-sync-failed}))

      :else
      (try
        (let [remote (select-sync-remote! ctx (:remote opts))
              remote-name (get remote "name")
              remote-type (remote-type remote)
              identity-path (some-> (get remote "identity") str/trim not-empty)
              vault-path (vault-path/resolve-vault-path ctx nil)
              local-exists? (.exists (io/file vault-path))
              _ (when (nil? identity-path)
                  (throw (ex-info "remote identity is not configured (set --identity on `remote add`)"
                                  {:reason reasons/reason-remote-identity-missing})))
              identity (bundle/load-identity {:identity-file identity-path
                                              :from-stdin? false
                                              :stdin nil})
              {:keys [remote_rev has_remote bundle_ref]} (remote-revision remote)
              _ (when-not has_remote
                  (throw (ex-info "remote bundle is missing"
                                  {:reason reasons/reason-remote-bundle-missing})))
              sync-entry (config/config-sync-entry (:config-path ctx) remote-name)
              baseline-secret-hashes-in (normalize-hash-map (get sync-entry "baseline_secret_hashes"))
              last-seen (some-> sync-entry (get "last_seen_rev") str/trim not-empty)
              local-hash-before (when local-exists? (file-sha256-hex vault-path))
              last-local-hash (some-> sync-entry (get "local_hash") str/trim not-empty)
              remote-changed (boolean (and last-seen remote_rev (not= last-seen remote_rev)))
              local-changed (boolean (and local-exists? last-local-hash local-hash-before (not= last-local-hash local-hash-before)))
              has-conflict (boolean (and remote-changed local-changed))
              reconcile? (boolean (:reconcile? opts))
              _ (when (and has-conflict (not reconcile?))
                  (throw (ex-info "local and remote have overlapping changes; rerun with --reconcile"
                                  {:reason reasons/reason-overlapping-changes
                                   :recommended_action "manual_reconcile"})))
              _ (when (and reconcile? has-conflict (nil? baseline-secret-hashes-in))
                  (throw (ex-info "cannot reconcile without baseline key hashes; run `kimen sync pull` first"
                                  {:reason reasons/reason-reconcile-baseline-missing
                                   :recommended_action "sync_pull"})))
              reconcile-passphrase (when (and reconcile? has-conflict)
                                     (resolve-passphrase! ctx opts))
              reconcile-local-snap (when reconcile-passphrase
                                     (load-vault-snapshot vault-path reconcile-passphrase false))
              reconcile-remote-snap (when reconcile-passphrase
                                      (load-remote-vault-snapshot remote reconcile-passphrase true))
              reconcile-analysis (when reconcile-passphrase
                                   (analyze-sync-changes baseline-secret-hashes-in
                                                         (:hashes reconcile-local-snap)
                                                         (:hashes reconcile-remote-snap)))
              would-backup (boolean (and local-exists? (not (:no-backup? opts))))
              _ (when (and reconcile-passphrase
                           (seq (:conflict-keys reconcile-analysis)))
                  (throw (ex-info "local and remote have overlapping key changes; manual reconciliation required"
                                  {:reason reasons/reason-overlapping-changes
                                   :recommended_action "manual_reconcile"})))]
          (if (:dry-run? opts)
            (let [payload {:ok true
                           :action "sync_pull_dry_run"
                           :exit_code 0
                           :remote remote-name
                           :remote_type remote-type
                           :remote_path (get remote "path")
                           :bundle_path bundle_ref
                           :vault_path vault-path
                           :remote_rev remote_rev
                           :dry_run true
                           :would_backup would-backup
                           :reconcile reconcile?
                           :remote_changed remote-changed
                           :local_changed local-changed
                           :has_conflict has-conflict
                           :has_remote has_remote
                           :has_local local-exists?}]
              (if json?
                (success-json payload)
                (result {:exit-code 0
                         :stdout (format "dry-run: would pull %s -> %s\n" bundle_ref vault-path)})))
            (let [backup-path (when (and local-exists? (not (:no-backup? opts)))
                                (let [p (str vault-path ".bak." (System/currentTimeMillis))]
                                  (copy-file! vault-path p)
                                  p))
                  _ (if reconcile-passphrase
                      (apply-sync-reconcile-merge! vault-path reconcile-passphrase reconcile-local-snap reconcile-remote-snap reconcile-analysis)
                      (let [{:keys [bundle-path cleanup]} (materialize-remote-bundle-for-read remote)]
                        (try
                          (bundle/open-to-vault-file bundle-path vault-path identity true)
                          (finally
                            (cleanup)))))
                  merged-key-count (if reconcile-passphrase
                                     (+ (count (:local-only-changed-keys reconcile-analysis))
                                        (count (:remote-only-changed-keys reconcile-analysis)))
                                     0)
                  _ (when-not (.exists (io/file vault-path))
                      (throw (ex-info "local vault missing after pull"
                                      {:reason reasons/reason-local-vault-missing-after-pull})))
                  local-hash (file-sha256-hex vault-path)
                  sync-passphrase (or reconcile-passphrase
                                      (maybe-sync-passphrase ctx opts))
                  baseline-secret-hashes (if reconcile-passphrase
                                           (:hashes reconcile-remote-snap)
                                           (when sync-passphrase
                                             (:hashes (load-vault-snapshot vault-path sync-passphrase false))))
                  _ (config/config-sync-mark-seen! (:config-path ctx) remote-name remote_rev local-hash baseline-secret-hashes)
                  payload {:ok true
                           :action (if reconcile? "sync_pull_reconcile" "sync_pull")
                           :exit_code 0
                           :remote remote-name
                           :remote_type remote-type
                           :remote_path (get remote "path")
                           :bundle_path bundle_ref
                           :vault_path vault-path
                           :remote_rev remote_rev
                           :last_seen_rev remote_rev
                           :local_hash local-hash
                           :has_baseline_hashes (boolean baseline-secret-hashes)
                           :reconcile reconcile?
                           :merged_key_count merged-key-count
                           :reconciled (boolean reconcile-passphrase)
                           :remote_changed remote-changed
                           :local_changed local-changed
                           :has_conflict has-conflict
                           :has_remote has_remote
                           :has_local true
                           :backup_path backup-path}]
              (if json?
                (success-json payload)
                (result {:exit-code 0
                         :stdout (format "ok (pulled %s)\n" remote-name)})))))
        (catch Exception e
          (sync-error-result json? e))))))

(defn- sync-transfer-args
  [{:keys [remote json? dry-run? force? reconcile? no-backup? passphrase-cmd passphrase-stdin?]}]
  (cond-> []
    (some? remote) (conj "--remote" remote)
    dry-run? (conj "--dry-run")
    force? (conj "--force")
    reconcile? (conj "--reconcile")
    no-backup? (conj "--no-backup")
    passphrase-stdin? (conj "--passphrase-stdin")
    (some? passphrase-cmd) (conj "--passphrase-cmd" passphrase-cmd)
    json? (conj "--json")))

(defn- sync-auto-passphrase-args
  [opts]
  (cond-> []
    (:passphrase-stdin? opts) (conj "--passphrase-stdin")
    (some? (:passphrase-cmd opts)) (into ["--passphrase-cmd" (:passphrase-cmd opts)])))

(defn- sync-auto-output
  [payload json? terse?]
  (if json?
    (result {:exit-code (:exit_code payload)
             :stdout (json-line payload)})
    (let [lines (if terse?
                  [(format "remote=%s decision=%s ok=%s exit_code=%d reason=%s recommended_action=%s"
                           (or (:remote payload) "-")
                           (:decision payload)
                           (:ok payload)
                           (:exit_code payload)
                           (or (:reason payload) "none")
                           (or (:recommended_action payload) "none"))
                   ""]
                  [(format "sync (mode=%s strict=%s)" (:mode payload) (:strict payload))
                   (when-let [remote (:remote payload)] (str "remote: " remote))
                   (str "decision: " (:decision payload))
                   (str "local-changed: " (:local_changed payload))
                   (when (:local_change_uncertain payload)
                     "local-change-uncertain: true")
                   (mapcat (fn [step]
                             (if (:ok step)
                               [(format "[ok] %s" (:name step))]
                               [(format "[fail] %s (exit=%d)" (:name step) (:exit_code step))
                                (when-let [m (:error step)] (str "  error: " m))
                                (when-let [a (:recommended_action step)] (str "  recommended-action: " a))]))
                           (:steps payload))
                   (when-let [a (:recommended_action payload)]
                     (str "recommended-action: " a))
                   (if (:ok payload)
                     "sync: ok"
                     (format "sync: failed (exit=%d)" (:exit_code payload)))
                   ""])]
      (result {:exit-code (:exit_code payload)
               :stdout (str/join "\n" (remove nil? (flatten lines)))}))))

(defn- detect-local-changes-since-baseline
  [status]
  (let [has-local (boolean (get status "has_local"))
        local-hash (some-> (get status "local_hash") str/trim not-empty)
        last-local-hash (some-> (get status "last_local_hash") str/trim not-empty)]
    (cond
      (not has-local)
      [false false]

      (and local-hash last-local-hash)
      [(not= local-hash last-local-hash) false]

      :else
      [true true])))

(defn- choose-sync-auto-decision
  [status local-changed local-change-uncertain]
  (let [needs-pull (boolean (get status "needs_pull"))
        has-local (boolean (get status "has_local"))
        can-push (boolean (get status "can_push"))
        lock-blocks-push (boolean (get status "lock_blocks_push"))
        blockers (->> (get status "blockers")
                      (filter string?)
                      vec)
        recommended (some-> (get status "recommended_action") str/trim not-empty)
        conflict (detect-sync-conflict (get status "last_seen_rev")
                                       (get status "remote_rev")
                                       (boolean (get status "has_remote")))
        conflict-reason (some-> (:reason conflict) str/trim not-empty)
        conflict-message (some-> (:message conflict) str/trim not-empty)]
    (cond
      needs-pull
      (cond
        (not has-local)
        ["pull" nil]

        (or local-changed local-change-uncertain)
        (cond
          local-change-uncertain
          (if (:has-conflict conflict)
            ["blocked" {:reason conflict-reason
                        :message (or conflict-message "sync is blocked by remote conflict")
                        :recommended_action (or (sync-state/recommended-action-for-reason conflict-reason)
                                                recommended
                                                "manual_review")
                        :expected_rev (:expected-rev conflict)
                        :actual_rev (:actual-rev conflict)}]
            ["blocked" {:reason reasons/reason-manual-pull-required
                        :message "remote pull required but local vault has unpushed changes; run `kimen sync pull` manually and re-apply local changes"
                        :recommended_action "sync_pull"}])

          (and (:has-conflict conflict)
               (not= conflict-reason reasons/reason-remote-changed))
          ["blocked" {:reason conflict-reason
                      :message (or conflict-message "sync is blocked by remote conflict")
                      :recommended_action (or (sync-state/recommended-action-for-reason conflict-reason)
                                              recommended
                                              "manual_review")
                      :expected_rev (:expected-rev conflict)
                      :actual_rev (:actual-rev conflict)}]

          :else
          ["pull_reconcile" nil])

        :else
        ["pull" nil])

      can-push
      [(if local-changed "push" "noop") nil]

      lock-blocks-push
      ["blocked" {:reason reasons/reason-remote-lock-present
                  :message "remote push lock exists; wait or remove lock with `kimen sync unlock`"
                  :recommended_action "wait_or_sync_unlock"}]

      (seq blockers)
      ["blocked" {:reason (first blockers)
                  :message (format "sync is blocked (blockers=%s)" (str/join "," blockers))
                  :recommended_action (or recommended "manual_review")}]

      :else
      ["noop" nil])))

(defn- handle-sync-auto
  [ctx args]
  (let [[opts parse-error] (parse-sync-auto-opts args)
        json? (:json? opts)]
    (cond
      parse-error
      (sync-error-result json? (ex-info parse-error {:reason reasons/reason-sync-failed}))

      (neg? (:stale-threshold-ms opts))
      (sync-error-result json? (ex-info "--stale-threshold must be >= 0"
                                        {:reason reasons/reason-invalid-stale-threshold}))

      (and (:check? opts) (:dry-run? opts))
      (sync-error-result json? (ex-info "--check and --dry-run cannot be used together"
                                        {:reason reasons/reason-conflicting-check-and-dry-run}))

      :else
      (try
        (let [result0 {:ok true
                       :action "sync"
                       :remote nil
                       :mode (cond
                               (:check? opts) "check"
                               (:dry-run? opts) "dry_run"
                               :else "apply")
                       :strict (boolean (:strict? opts))
                       :dry_run (boolean (:dry-run? opts))
                       :check (boolean (:check? opts))
                       :no_doctor (boolean (:no-doctor? opts))
                       :decision "noop"
                       :exit_code 0
                       :local_changed false
                       :steps []}
              run-check (fn [check-name argv]
                          (run-sync-preflight-check ctx check-name argv))
              fail-with-step (fn [res step]
                               (let [res (update res :steps conj step)
                                     code (if (zero? (:exit_code step))
                                            exit-code/code-sync-failed
                                            (:exit_code step))
                                     reason (or (some-> step :payload (get "reason") str/trim not-empty)
                                                reasons/reason-sync-failed)
                                     recommended (or (:recommended_action step)
                                                     (some-> step :payload (get "recommended_action") str/trim not-empty)
                                                     "manual_review")]
                                 (assoc res
                                        :ok false
                                        :decision "blocked"
                                        :exit_code code
                                        :reason reason
                                        :recommended_action recommended)))
              doctor-step (when-not (:no-doctor? opts)
                            (run-check sync-preflight-check-doctor
                                       (build-sync-preflight-check-args sync-preflight-check-doctor opts)))
              result1 (if doctor-step
                        (update result0 :steps conj doctor-step)
                        result0)]
          (if (and doctor-step (not (:ok doctor-step)))
            (sync-auto-output (fail-with-step result0 doctor-step) json? (:terse? opts))
            (let [status-step (run-check sync-preflight-check-status
                                         (build-sync-preflight-check-args sync-preflight-check-status opts))
                  result2 (update result1 :steps conj status-step)]
              (if-not (:ok status-step)
                (sync-auto-output (fail-with-step result1 status-step) json? (:terse? opts))
                (let [status (:payload status-step)
                      status-raw (some-> (:payload_raw status-step) str/trim not-empty)
                      _ (when-not (map? status)
                          (if status-raw
                            (throw (ex-info (format "decode sync status payload: %s" status-raw)
                                            {:reason reasons/reason-sync-status-decode-failed}))
                            (throw (ex-info "sync status returned empty payload"
                                            {:reason reasons/reason-sync-status-empty-payload}))))
                      _ (when-not (some-> (get status "action") str/trim not-empty)
                          (throw (ex-info "sync status payload missing action"
                                          {:reason reasons/reason-sync-status-missing-action})))
                      remote-name (some-> (get status "remote") str/trim not-empty)
                      [local-changed local-change-uncertain] (detect-local-changes-since-baseline status)
                      [decision-base decision-error] (choose-sync-auto-decision status local-changed local-change-uncertain)
                      result3 (assoc result2
                                     :remote remote-name
                                     :status status
                                     :local_changed local-changed
                                     :local_change_uncertain local-change-uncertain
                                     :recommended_action (some-> (get status "recommended_action") str/trim not-empty)
                                     :decision decision-base)]
                  (cond
                    (some? decision-error)
                    (let [reason (or (:reason decision-error) reasons/reason-sync-failed)
                          message (or (:message decision-error) "sync is blocked")
                          code (sync-exit-code-for-reason reason)]
                      (sync-auto-output (assoc result3
                                               :ok false
                                               :decision "blocked"
                                               :exit_code code
                                               :reason reason
                                               :error message
                                               :recommended_action (or (:recommended_action decision-error)
                                                                       (:recommended_action result3)
                                                                       "manual_review")
                                               :expected_rev (:expected_rev decision-error)
                                               :actual_rev (:actual_rev decision-error))
                                        json?
                                        (:terse? opts)))

                    (:check? opts)
                    (let [decision (case decision-base
                                     "push" "would_push"
                                     "pull" "would_pull"
                                     "pull_reconcile" "would_pull_reconcile"
                                     decision-base)]
                      (sync-auto-output (assoc result3 :decision decision) json? (:terse? opts)))

                    (= "noop" decision-base)
                    (sync-auto-output result3 json? (:terse? opts))

                    :else
                    (let [passphrase-args (sync-auto-passphrase-args opts)
                          base-sync-args (cond-> ["--remote" remote-name]
                                           (:force? opts) (conj "--force")
                                           (:reconcile? opts) (conj "--reconcile"))
                          [step-name argv decision]
                          (if (:dry-run? opts)
                            (case decision-base
                              "push" [sync-preflight-check-push-dry-run
                                      (vec (concat ["sync" "push"] base-sync-args ["--dry-run"] passphrase-args ["--json"]))
                                      "would_push"]
                              "pull" [sync-preflight-check-pull-dry-run
                                      (vec (concat ["sync" "pull"] base-sync-args ["--dry-run"] passphrase-args ["--json"]))
                                      "would_pull"]
                              "pull_reconcile" [sync-preflight-check-pull-dry-run
                                                (vec (concat ["sync" "pull"] base-sync-args ["--reconcile" "--dry-run"] passphrase-args ["--json"]))
                                                "would_pull_reconcile"])
                            (case decision-base
                              "push" ["sync_push"
                                      (vec (concat ["sync" "push"] base-sync-args passphrase-args ["--json"]))
                                      "push"]
                              "pull" ["sync_pull"
                                      (vec (concat ["sync" "pull"] base-sync-args passphrase-args ["--json"]))
                                      "pull"]
                              "pull_reconcile" ["sync_pull_reconcile"
                                                (vec (concat ["sync" "pull"] base-sync-args ["--reconcile"] passphrase-args ["--json"]))
                                                "pull_reconcile"]))
                          step (run-check step-name argv)
                          result4 (-> result3
                                      (assoc :decision decision)
                                      (update :steps conj step))]
                      (if (:ok step)
                        (sync-auto-output result4 json? (:terse? opts))
                        (sync-auto-output (fail-with-step result3 step) json? (:terse? opts))))))))))
        (catch Exception e
          (sync-error-result json? e))))))

(defn- parse-json-map
  [s]
  (let [s (some-> s str/trim)]
    (when-not (str/blank? s)
      (try
        (let [v (json/read-str s)]
          (when (map? v)
            v))
        (catch Exception _ nil)))))

(defn- run-sync-preflight-check
  [ctx check-name argv]
  (let [res (run ctx argv)
        code (:exit-code res)
        stdout (some-> (:stdout res) str/trim)
        stderr (some-> (:stderr res) str/trim)
        payload-raw (cond
                      (and (not (zero? code)) (not (str/blank? stderr)))
                      stderr

                      (not (str/blank? stdout))
                      stdout

                      :else
                      stderr)
        payload-raw-trimmed (some-> payload-raw str/trim not-empty)
        payload (parse-json-map payload-raw)
        error-msg (when-not (zero? code)
                    (or (some-> payload (get "error") str/trim not-empty)
                        (not-empty payload-raw)))
        recommended-action (when-not (zero? code)
                             (some-> payload (get "recommended_action") str/trim not-empty))]
    (cond-> {:name check-name
             :command (str "kimen " (str/join " " argv))
             :ok (zero? code)
             :exit_code code}
      (some? payload-raw-trimmed) (assoc :payload_raw payload-raw-trimmed)
      (some? error-msg) (assoc :error error-msg)
      (some? recommended-action) (assoc :recommended_action recommended-action)
      (some? payload) (assoc :payload payload))))

(defn- build-sync-preflight-result
  [checks remote-name strict?]
  (let [failed (->> checks
                    (remove :ok)
                    vec)
        failed-checks (mapv :name failed)
        failed-check (first failed-checks)
        has-conflict? (some #(= exit-code/code-sync-conflict (:exit_code %)) failed)
        exit-code (cond
                    has-conflict? exit-code/code-sync-conflict
                    (seq failed) exit-code/code-sync-failed
                    :else 0)
        recommended (some (fn [check]
                            (some-> (:recommended_action check) str/trim not-empty))
                          failed)]
    (cond-> {:ok (zero? exit-code)
             :action "sync_preflight"
             :strict (boolean strict?)
             :exit_code exit-code
             :check_count (count checks)
             :failed_count (count failed-checks)
             :failed_checks failed-checks
             :failed_check failed-check
             :checks checks}
      (some? remote-name) (assoc :remote remote-name)
      (some? recommended) (assoc :recommended_action recommended))))

(defn- render-sync-preflight-human
  [payload]
  (let [checks (or (:checks payload) [])
        lines (into [(format "sync preflight (strict=%s)" (:strict payload))
                     (when-let [remote (:remote payload)]
                       (str "remote: " remote))]
                    (concat
                     (mapcat (fn [check]
                               (if (:ok check)
                                 [(format "[ok] %s" (:name check))]
                                 [(format "[fail] %s (exit=%d)" (:name check) (:exit_code check))
                                  (when-let [m (:error check)]
                                    (str "  error: " m))
                                  (when-let [a (:recommended_action check)]
                                    (str "  recommended-action: " a))]))
                             checks)
                     [(format "failed-checks: %d/%d"
                              (:failed_count payload)
                              (:check_count payload))
                      (when-let [a (:recommended_action payload)]
                        (str "recommended-action: " a))
                      (if (:ok payload)
                        "preflight: ok"
                        (format "preflight: failed (exit=%d)" (:exit_code payload)))
                      ""]))]
    (str/join "\n" (remove nil? lines))))

(defn- handle-sync-preflight
  [ctx args]
  (let [[opts parse-error] (parse-sync-preflight-opts args)
        json? (:json? opts)]
    (if parse-error
      (sync-error-result json? (ex-info parse-error {:reason reasons/reason-sync-failed}))
      (try
        (when (neg? (:stale-threshold-ms opts))
          (throw (ex-info "--stale-threshold must be >= 0"
                          {:reason reasons/reason-invalid-stale-threshold})))
        (let [selected-checks (resolve-sync-preflight-checks! (:only opts) (:skip opts))
              checks (mapv (fn [check-name]
                             (let [argv (build-sync-preflight-check-args check-name opts)]
                               (run-sync-preflight-check ctx check-name argv)))
                           selected-checks)
              remote-name (some-> (:remote opts) str/trim not-empty)
              payload (build-sync-preflight-result checks remote-name (:strict? opts))]
          (if json?
            (result {:exit-code (:exit_code payload)
                     :stdout (json-line payload)})
            (result {:exit-code (:exit_code payload)
                     :stdout (render-sync-preflight-human payload)})))
        (catch Exception e
          (sync-error-result json? e))))))

(defn- handle-sync-unlock
  [ctx args]
  (let [[opts parse-error] (parse-sync-unlock-opts args)
        json? (:json? opts)]
    (if parse-error
      (sync-error-result json? (ex-info parse-error {:reason reasons/reason-sync-failed}))
      (try
        (when (neg? (:if-older-than-ms opts))
          (throw (ex-info "--if-older-than must be >= 0"
                          {:reason reasons/reason-invalid-if-older-than})))
        (let [remote (-> (select-sync-remote! ctx (:remote opts))
                         ensure-fs-remote!)
              remote-name (get remote "name")
              lock-path (remote-lock-path remote)
              _ (when (str/blank? lock-path)
                  (throw (ex-info "remote lock path is empty" {:reason reasons/reason-missing-path})))
              lock-file (io/file lock-path)]
          (if-not (.exists lock-file)
            (if json?
              (success-json {:ok true
                             :action "sync_unlock"
                             :exit_code 0
                             :remote remote-name
                             :lock_path lock-path
                             :removed false
                             :reason reasons/reason-lock-missing})
              (result {:exit-code 0
                       :stdout (format "no lock file found for remote %s\n" remote-name)}))
            (let [lock-age-ms (max 0 (- (System/currentTimeMillis) (.lastModified lock-file)))
                  if-older-than-ms (long (:if-older-than-ms opts))
                  _ (when (and (pos? if-older-than-ms)
                               (< lock-age-ms if-older-than-ms))
                      (throw (ex-info (format "refusing to unlock %s: lock is only %ds old (requires >= %ds)"
                                              lock-path
                                              (quot lock-age-ms 1000)
                                              (quot if-older-than-ms 1000))
                                      {:reason reasons/reason-lock-too-new
                                       :recommended_action "wait_or_sync_unlock"})))
                  _ (when-not (:yes? opts)
                      (throw (ex-info "refusing to remove lock without --yes"
                                      {:reason reasons/reason-unlock-confirmation-required})))
                  removed? (.delete lock-file)
                  lock-missing-after? (not (.exists lock-file))
                  age-str (str (quot lock-age-ms 1000) "s")]
              (if lock-missing-after?
                (if json?
                  (success-json (cond-> {:ok true
                                         :action "sync_unlock"
                                         :exit_code 0
                                         :remote remote-name
                                         :lock_path lock-path
                                         :removed removed?}
                                  (not removed?) (assoc :reason reasons/reason-lock-missing)
                                  removed? (assoc :lock_age age-str
                                                  :confirmed true)))
                  (if removed?
                    (result {:exit-code 0
                             :stdout (format "removed lock for remote %s: %s (age=%s)\n"
                                             remote-name
                                             lock-path
                                             age-str)})
                    (result {:exit-code 0
                             :stdout (format "no lock file found for remote %s\n" remote-name)})))
                (throw (ex-info (format "failed to remove lock %s" lock-path)
                                {:reason reasons/reason-sync-failed}))))))
        (catch Exception e
          (sync-error-result json? e))))))

(defn- handle-sync-reset-baseline
  [ctx args]
  (let [[opts parse-error] (parse-sync-reset-baseline-opts args)
        json? (:json? opts)]
    (if parse-error
      (sync-error-result json? (ex-info parse-error {:reason reasons/reason-sync-failed}))
      (try
        (let [remote (select-sync-remote! ctx (:remote opts))
              remote-name (get remote "name")
              clear? (:clear? opts)
              to-remote? (:to-remote? opts)
              rev (some-> (:rev opts) str/trim not-empty)
              selected-modes (count (filter identity [clear? to-remote? (some? rev)]))
              existing-entry (config/config-sync-entry (:config-path ctx) remote-name)
              previous-rev (some-> existing-entry (get "last_seen_rev") str/trim not-empty)]
          (when-not (:yes? opts)
            (throw (ex-info "refusing to reset baseline without --yes"
                            {:reason reasons/reason-reset-baseline-confirmation-required})))
          (when (not= selected-modes 1)
            (throw (ex-info "choose exactly one mode: --to-remote, --clear, or --rev <sha256>"
                            {:reason reasons/reason-invalid-reset-baseline-mode})))
          (if clear?
            (do
              (config/config-sync-clear! (:config-path ctx) remote-name)
              (if json?
                (success-json {:ok true
                               :action "sync_reset_baseline"
                               :exit_code 0
                               :remote remote-name
                               :mode "clear"
                               :previous_rev previous-rev
                               :new_rev nil})
                (result {:exit-code 0
                         :stdout (format "ok (sync baseline cleared for %s)\n" remote-name)})))
            (let [new-rev (if (some? rev)
                            rev
                            (let [{:keys [remote_rev has_remote]} (remote-revision remote)
                                  _ (when-not has_remote
                                      (throw (ex-info "remote bundle is missing; cannot set baseline to remote"
                                                      {:reason reasons/reason-remote-bundle-missing-for-baseline})))]
                              remote_rev))
                  vault-path (vault-path/resolve-vault-path ctx nil)
                  local-hash (when (.exists (io/file vault-path))
                               (file-sha256-hex vault-path))
                  sync-passphrase (when to-remote?
                                    (maybe-sync-passphrase ctx opts))
                  baseline-secret-hashes (when sync-passphrase
                                           (:hashes (load-remote-vault-snapshot remote sync-passphrase false)))
                  _ (config/config-sync-mark-seen! (:config-path ctx) remote-name new-rev local-hash baseline-secret-hashes)]
              (if json?
                (success-json {:ok true
                               :action "sync_reset_baseline"
                               :exit_code 0
                               :remote remote-name
                               :mode (if to-remote? "to_remote" "rev")
                               :previous_rev previous-rev
                               :new_rev new-rev
                               :remote_rev (when to-remote? new-rev)
                               :local_hash local-hash
                               :has_baseline_hashes (boolean baseline-secret-hashes)})
                (result {:exit-code 0
                         :stdout (if to-remote?
                                   (format "ok (sync baseline set to remote for %s)\n" remote-name)
                                   (format "ok (sync baseline set to explicit rev for %s)\n" remote-name))})))))
        (catch Exception e
          (sync-error-result json? e))))))

(defn- handle-sync-restore
  [ctx args]
  (let [[opts parse-error] (parse-sync-restore-opts args)
        json? (:json? opts)]
    (if parse-error
      (sync-error-result json? (ex-info parse-error {:reason reasons/reason-sync-failed}))
      (try
        (let [backup-path (some-> (:backup opts) str/trim)
              _ (when (str/blank? backup-path)
                  (throw (ex-info "--backup is required"
                                  {:reason reasons/reason-missing-backup})))
              backup-file (io/file backup-path)
              _ (when-not (.exists backup-file)
                  (throw (ex-info (format "backup file missing: %s" backup-path)
                                  {:reason reasons/reason-input-missing})))
              vault-path (vault-path/resolve-vault-path ctx nil)
              current-backup-path (when (and (not (:no-backup? opts))
                                             (.exists (io/file vault-path)))
                                    (let [p (str vault-path ".bak." (System/currentTimeMillis))]
                                      (copy-file! vault-path p)
                                      p))
              _ (copy-file! backup-path vault-path)]
          (if json?
            (success-json {:ok true
                           :action "sync_restore"
                           :exit_code 0
                           :source_backup_path backup-path
                           :current_backup_path current-backup-path
                           :backup_path backup-path
                           :vault_path vault-path
                           :restored true})
            (result {:exit-code 0
                     :stdout (format "ok (restored %s)\n" backup-path)})))
        (catch Exception e
          (sync-error-result json? e))))))

(defn- handle-sync-init
  [ctx args]
  (let [[opts parse-error] (parse-sync-init-opts args)
        json? (:json? opts)]
    (if parse-error
      (sync-error-result json? (ex-info parse-error {:reason reasons/reason-sync-failed}))
      (try
        (let [remote-name (validate-remote-name! (:remote opts))
              existing (try
                         (config/config-remote-get (:config-path ctx) remote-name)
                         (catch clojure.lang.ExceptionInfo e
                           (if (= reasons/reason-remote-not-found (:reason (ex-data e)))
                             nil
                             (throw e))))
              _ (when (and existing (not (:update? opts)))
                  (throw (ex-info (format "remote %s already exists (use --update)" (pr-str remote-name))
                                  {:reason reasons/reason-remote-exists})))
              type (if (:type-set? opts)
                     (config/normalize-remote-type (:type opts))
                     (or (some-> (get existing "type") str/trim not-empty) "fs"))
              _ (when-not (#{"fs" "git"} type)
                  (throw (ex-info (format "unsupported remote type %s (expected fs or git)" (pr-str type))
                                  {:reason reasons/reason-unsupported-remote-type})))
              path (if (:path-set? opts)
                     (some-> (:path opts) str/trim)
                     (some-> (get existing "path") str/trim))
              _ (when (and (nil? existing) (str/blank? path))
                  (throw (ex-info "--path is required" {:reason reasons/reason-missing-path})))
              recipient (if (:recipient-set? opts)
                          (some-> (:recipient opts) str/trim)
                          (some-> (get existing "recipient") str/trim))
              identity (if (:identity-set? opts)
                         (some-> (:identity opts) str/trim)
                         (some-> (get existing "identity") str/trim))
              branch (if (:branch-set? opts)
                       (some-> (:branch opts) str/trim)
                       (some-> (get existing "branch") str/trim))
              bundle-path (if (:bundle-path-set? opts)
                            (some-> (:bundle-path opts) str/trim)
                            (some-> (get existing "bundle_path") str/trim))
              _ (when (and (not= type "git")
                           (or (:branch-set? opts) (:bundle-path-set? opts)))
                  (throw (ex-info "--branch/--bundle-path are only valid for --type git"
                                  {:reason reasons/reason-git-fields-require-git-type})))
              should-derive? (and (not (:recipient-set? opts))
                                  (str/blank? recipient)
                                  (not (str/blank? identity)))
              recipient (if should-derive?
                          (config/derive-recipient-from-identity-file identity)
                          recipient)
              derived-recipient? (and should-derive? (not (str/blank? recipient)))
              remote-map (build-remote-map {:name remote-name
                                            :type type
                                            :path path
                                            :recipient recipient
                                            :identity identity
                                            :branch branch
                                            :bundle-path bundle-path})
              {:keys [remote-config created updated baseline-reset]}
              (if existing
                (let [{:keys [remote baseline-reset?]} (config/config-remote-set! (:config-path ctx) remote-name remote-map)]
                  {:remote-config remote
                   :created false
                   :updated true
                   :baseline-reset baseline-reset?})
                {:remote-config (config/config-remote-add! (:config-path ctx) remote-map)
                 :created true
                 :updated false
                 :baseline-reset false})
              check-fields (if (:no-check? opts)
                             {:check_skipped true}
                             (sync-init-check ctx remote-name))
              payload (merge {:ok true
                              :action "sync_init"
                              :exit_code 0
                              :remote remote-name
                              :created created
                              :updated updated
                              :derived_recipient derived-recipient?
                              :baseline_reset baseline-reset
                              :remote_config remote-config}
                             check-fields)]
          (if json?
            (success-json payload)
            (let [recipient* (some-> (get remote-config "recipient") str/trim not-empty)
                  identity* (some-> (get remote-config "identity") str/trim not-empty)
                  lines [(if created
                           (format "ok (sync remote %s created)" remote-name)
                           (format "ok (sync remote %s updated)" remote-name))
                         (str "type: " (get remote-config "type"))
                         (str "path: " (get remote-config "path"))
                         (when (= "git" (get remote-config "type"))
                           (str "branch: " (get remote-config "branch")))
                         (when (= "git" (get remote-config "type"))
                           (str "bundle-path: " (get remote-config "bundle_path")))
                         (if recipient*
                           (str "recipient: " recipient*)
                           "recipient: (none)")
                         (if identity*
                           (str "identity: " identity*)
                           "identity: (none)")
                         (when derived-recipient?
                           "derived-recipient: true")
                         (when baseline-reset
                           "sync-baseline-reset: true")
                         (when (:check_skipped payload)
                           "status-check: skipped (--no-check)")
                         (when (:check_ok payload)
                           "status: ok")
                         (when-let [recommended-action (:recommended_action payload)]
                           (str "recommended-action: " recommended-action))
                         (when-let [next-command (:next_command payload)]
                           (str "next: " next-command))]]
              (result {:exit-code 0
                       :stdout (str (str/join "\n" (remove nil? lines)) "\n")}))))
        (catch Exception e
          (sync-error-result json? e))))))

(defn- handle-sync
  [ctx args]
  (let [args (vec args)]
    (cond
      (help-arg? (first args))
      (help-result sync-usage)

      (or (empty? args)
          (str/starts-with? (first args) "-")) (handle-sync-auto ctx args)
      (= "init" (first args)) (handle-sync-init ctx (rest args))
      (= "preflight" (first args)) (handle-sync-preflight ctx (rest args))
      (= "status" (first args)) (handle-sync-status ctx (rest args))
      (= "conflicts" (first args)) (handle-sync-conflicts ctx (rest args))
      (= "changes" (first args)) (handle-sync-changes ctx (rest args))
      (= "resolve" (first args)) (handle-sync-resolve ctx (rest args))
      (= "push" (first args)) (handle-sync-push ctx (rest args))
      (= "pull" (first args)) (handle-sync-pull ctx (rest args))
      (= "reset-baseline" (first args)) (handle-sync-reset-baseline ctx (rest args))
      (= "unlock" (first args)) (handle-sync-unlock ctx (rest args))
      (= "restore" (first args)) (handle-sync-restore ctx (rest args))
      :else (error-with-help 1 (str "unknown sync command " (pr-str (first args))) sync-usage))))

(defn- parse-cmd-string
  [s]
  (->> (str/split (str/trim (str s)) #"\s+")
       (remove str/blank?)
       vec))

(defn- read-passphrase-line!
  [input reason message]
  (let [br (java.io.BufferedReader. (java.io.InputStreamReader. input))
        line (some-> (.readLine br) str/trim)]
    (if (str/blank? line)
      (throw (ex-info message {:reason reason}))
      line)))

(defn- read-passphrase-file!
  [path reason message]
  (let [line (-> (slurp path) str/split-lines first str/trim)]
    (if (str/blank? line)
      (throw (ex-info message {:reason reason}))
      line)))

(defn- passphrase-from-cmd!
  [cmd-str empty-msg missing-msg missing-reason]
  (when (str/blank? cmd-str)
    (throw (ex-info empty-msg {:reason reasons/reason-empty-passphrase-command})))
  (let [argv (parse-cmd-string cmd-str)]
    (when (empty? argv)
      (throw (ex-info empty-msg {:reason reasons/reason-empty-passphrase-command})))
    (let [[cmd & args] argv
          {:keys [exit out err]} (apply sh/sh (concat [cmd] args [:out :string :err :string]))]
      (when-not (zero? exit)
        (throw (ex-info (format "passphrase command failed: %s"
                                (or (some-> err str/trim not-empty)
                                    (some-> out str/trim not-empty)
                                    "non-zero exit"))
                        {:reason reasons/reason-passphrase-command-failed})))
      (let [line (some-> out str/split-lines first str/trim)]
        (if (str/blank? line)
          (throw (ex-info missing-msg {:reason missing-reason}))
          line)))))

(defn- prompt-new-passphrase-for-rekey!
  []
  (if-let [console (System/console)]
    (let [pass-a (.readPassword console "New passphrase: " (object-array 0))
          pass-b (.readPassword console "Confirm new passphrase: " (object-array 0))
          a (some-> pass-a String. str/trim)
          b (some-> pass-b String. str/trim)]
      (cond
        (str/blank? a)
        (throw (ex-info "empty new passphrase"
                        {:reason reasons/reason-empty-new-passphrase}))

        (not= a b)
        (throw (ex-info "new passphrase confirmation does not match"
                        {:reason reasons/reason-new-passphrase-mismatch}))

        :else
        a))
    (throw (ex-info "no new passphrase provided (use --new-passphrase-file/--new-passphrase-cmd/--new-passphrase-stdin/--new-passphrase-env or run in a tty)"
                    {:reason reasons/reason-missing-new-passphrase}))))

(defn- resolve-old-passphrase-for-rekey!
  [ctx opts]
  (let [old-file-set? (some? (:old-passphrase-file opts))
        old-file (some-> (:old-passphrase-file opts) str/trim)]
    (when (and old-file-set?
               (or (:passphrase-stdin? opts)
                   (some-> (:passphrase-cmd opts) str/trim not-empty)))
      (throw (ex-info "use only one old passphrase source: --passphrase-cmd, --passphrase-stdin, or --old-passphrase-file"
                      {:reason reasons/reason-conflicting-passphrase-sources})))
    (if old-file-set?
      (read-passphrase-file! old-file
                             reasons/reason-missing-passphrase
                             "no passphrase provided")
      (resolve-passphrase! ctx opts))))

(defn- resolve-new-passphrase-for-rekey!
  [ctx opts]
  (let [new-cmd-set? (some? (:new-passphrase-cmd opts))
        new-file-set? (some? (:new-passphrase-file opts))
        new-env-set? (some? (:new-passphrase-env opts))
        new-stdin? (:new-passphrase-stdin? opts)
        source-count (count (filter true? [new-cmd-set? new-file-set? new-env-set? new-stdin?]))]
    (when (> source-count 1)
      (throw (ex-info "use only one new passphrase source: --new-passphrase-cmd, --new-passphrase-stdin, --new-passphrase-file, or --new-passphrase-env"
                      {:reason reasons/reason-conflicting-passphrase-sources})))
    (cond
      new-file-set?
      (read-passphrase-file! (some-> (:new-passphrase-file opts) str/trim)
                             reasons/reason-empty-new-passphrase
                             "empty new passphrase")

      new-cmd-set?
      (passphrase-from-cmd! (:new-passphrase-cmd opts)
                            "empty --new-passphrase-cmd"
                            "empty new passphrase"
                            reasons/reason-empty-new-passphrase)

      new-stdin?
      (read-passphrase-line! (or (:stdin ctx) System/in)
                             reasons/reason-empty-new-passphrase
                             "empty new passphrase")

      new-env-set?
      (let [env-name (some-> (:new-passphrase-env opts) str/trim)
            env-value (some-> (getenv ctx env-name) str)]
        (if (str/blank? (some-> env-value str/trim))
          (throw (ex-info (format "new passphrase environment variable %s is empty or unset" env-name)
                          {:reason reasons/reason-missing-new-passphrase}))
          env-value))

      :else
      (prompt-new-passphrase-for-rekey!))))

(defn- vault-dir-file
  [vault-path]
  (or (.getParentFile (io/file vault-path))
      (io/file ".")))

(defn- vault-rekey-backup-dir-file
  [vault-path backup-dir]
  (if-let [d (some-> backup-dir str/trim not-empty)]
    (io/file d)
    (vault-dir-file vault-path)))

(defn- assert-backup-dir-exists!
  [vault-path backup-dir]
  (let [dir-file (vault-rekey-backup-dir-file vault-path backup-dir)
        dir-path (.getPath dir-file)]
    (when-not (.exists dir-file)
      (throw (ex-info (format "backup directory not found: %s" dir-path)
                      {:reason reasons/reason-vault-failed})))
    (when-not (.isDirectory dir-file)
      (throw (ex-info (format "backup directory is not a directory: %s" dir-path)
                      {:reason reasons/reason-vault-failed})))
    dir-file))

(defn- same-directory?
  [a b]
  (= (.getCanonicalPath (io/file a))
     (.getCanonicalPath (io/file b))))

(defn- vault-rekey-preflight!
  [vault-path old-passphrase backup-enabled? backup-dir dry-run?]
  (vault-v2/list-secret-names vault-path old-passphrase)
  (when (and backup-enabled? dry-run?)
    (assert-backup-dir-exists! vault-path backup-dir))
  backup-enabled?)

(defn- create-vault-backup!
  [vault-path backup-dir]
  (let [vault-file (io/file vault-path)
        vault-dir (vault-dir-file vault-path)
        backup-dir-file (vault-rekey-backup-dir-file vault-path backup-dir)
        _ (when (and (not (.exists backup-dir-file))
                     (not (.mkdirs backup-dir-file)))
            (throw (ex-info (format "failed to create backup directory %s" (.getPath backup-dir-file))
                            {:reason reasons/reason-vault-failed})))
        _ (when-not (.isDirectory backup-dir-file)
            (throw (ex-info (format "backup directory is not a directory: %s" (.getPath backup-dir-file))
                            {:reason reasons/reason-vault-failed})))
        suffix (System/nanoTime)
        backup-path (if (same-directory? backup-dir-file vault-dir)
                      (format "%s.bak.%d" vault-path suffix)
                      (.getPath (io/file backup-dir-file (format "%s.bak.%d" (.getName vault-file) suffix))))]
    (copy-file! vault-path backup-path)
    backup-path))

(defn- handle-vault-path
  [ctx args]
  (let [[opts parse-error] (parse-vault-auth-opts args)
        json? (:json? opts)]
    (if parse-error
      (vault-error-result json? (ex-info parse-error {:reason reasons/reason-vault-failed}))
      (try
        (let [path (vault-path/resolve-vault-path ctx (:vault-path opts))]
          (if json?
            (success-json {:ok true
                           :action "vault_path"
                           :exit_code 0
                           :path path})
            (result {:exit-code 0
                     :stdout (str path "\n")})))
        (catch Exception e
          (vault-error-result json? e))))))

(defn- handle-vault-init
  [ctx args]
  (let [[opts parse-error] (parse-vault-auth-opts args)
        json? (:json? opts)]
    (cond
      parse-error
      (vault-error-result json? (ex-info parse-error {:reason reasons/reason-vault-failed}))

      (seq (:rest opts))
      (vault-error-result json? (ex-info "unexpected argument" {:reason reasons/reason-vault-failed}))

      :else
      (try
        (let [path (vault-path/resolve-vault-path ctx (:vault-path opts))
              pp (resolve-passphrase! ctx opts)]
          (vault-v2/init-vault! path pp)
          (if json?
            (success-json {:ok true
                           :action "vault_init"
                           :exit_code 0
                           :path path})
            (result {:exit-code 0
                     :stdout "ok\n"})))
        (catch Exception e
          (vault-error-result json? e))))))

(defn- handle-vault-info
  [ctx args]
  (let [[opts parse-error] (parse-vault-auth-opts args)
        json? (:json? opts)]
    (cond
      parse-error
      (vault-error-result json? (ex-info parse-error {:reason reasons/reason-vault-failed}))

      (seq (:rest opts))
      (vault-error-result json? (ex-info "unexpected argument" {:reason reasons/reason-vault-failed}))

      :else
      (try
        (let [path (vault-path/resolve-vault-path ctx (:vault-path opts))
              info (vault-v2/vault-info path)]
          (if json?
            (success-json (merge {:ok true
                                  :action "vault_info"
                                  :exit_code 0
                                  :path path}
                                 info))
            (result {:exit-code 0
                     :stdout (str (json/write-str info) "\n")})))
        (catch Exception e
          (vault-error-result json? e))))))

(defn- handle-vault-rekey
  [ctx args]
  (let [[opts parse-error] (parse-vault-rekey-opts args)
        json? (:json? opts)]
    (cond
      parse-error
      (vault-error-result json? (ex-info parse-error {:reason reasons/reason-vault-failed}))

      (seq (:rest opts))
      (vault-error-result json? (ex-info "unexpected argument" {:reason reasons/reason-vault-failed}))

      (and (:passphrase-stdin? opts) (:new-passphrase-stdin? opts))
      (vault-error-result json? (ex-info "cannot use both --passphrase-stdin and --new-passphrase-stdin"
                                         {:reason reasons/reason-conflicting-passphrase-sources}))

      (and (:no-backup? opts)
           (some-> (:backup-dir opts) str/trim not-empty))
      (vault-error-result json? (ex-info "--no-backup cannot be used with --backup-dir"
                                         {:reason reasons/reason-conflicting-backup-options}))

      :else
      (try
        (let [vault-path (vault-path/resolve-vault-path ctx (:vault-path opts))
              old-passphrase (resolve-old-passphrase-for-rekey! ctx opts)
              new-passphrase (resolve-new-passphrase-for-rekey! ctx opts)
              _ (when (= old-passphrase new-passphrase)
                  (throw (ex-info "new passphrase must differ from old passphrase"
                                  {:reason reasons/reason-new-passphrase-unchanged})))
              would-backup (vault-rekey-preflight! vault-path
                                                   old-passphrase
                                                   (not (:no-backup? opts))
                                                   (:backup-dir opts)
                                                   (:dry-run? opts))]
          (if (:dry-run? opts)
            (if json?
              (success-json {:ok true
                             :action "vault_rekey"
                             :exit_code 0
                             :path vault-path
                             :dry_run true
                             :would_backup would-backup})
              (result {:exit-code 0
                       :stdout (str (format "dry-run ok: vault can be rekeyed at %s\n" vault-path)
                                    (format "would-backup: %s\n" would-backup))}))
            (let [backup-path (when would-backup
                                (create-vault-backup! vault-path (:backup-dir opts)))
                  _ (vault-v2/rekey-vault! vault-path old-passphrase new-passphrase)]
              (if json?
                (success-json (cond-> {:ok true
                                       :action "vault_rekey"
                                       :exit_code 0
                                       :path vault-path}
                                backup-path (assoc :backup_path backup-path)))
                (result {:exit-code 0
                         :stdout (str (format "rekeyed vault at %s\n" vault-path)
                                      (when backup-path
                                        (format "backup: %s\n" backup-path)))})))))
        (catch Exception e
          (vault-error-result json? e))))))

(defn- handle-vault
  [ctx args]
  (let [args (vec args)]
    (cond
      (or (empty? args) (help-arg? (first args)))
      (help-result vault-usage)

      (= "init" (first args)) (handle-vault-init ctx (rest args))
      (= "info" (first args)) (handle-vault-info ctx (rest args))
      (= "path" (first args)) (handle-vault-path ctx (rest args))
      (= "rekey" (first args)) (handle-vault-rekey ctx (rest args))
      :else (error-with-help 1 (str "unknown vault command " (pr-str (first args))) vault-usage))))

(defn- secret-vault-path
  [ctx opts]
  (vault-path/resolve-vault-path ctx (:vault-path opts)))

(defn- secret-json-success
  [payload]
  (success-json (assoc payload :ok true :exit_code 0)))

(defn- handle-secret-set
  [ctx args]
  (let [args (vec args)]
    (if (help-arg? (first args))
      (help-result (str secret-set-usage-line "\n"))
      (let [[opts parse-error] (parse-secret-set-opts args)
            json? (:json? opts)]
        (cond
          parse-error
          (secret-error-result json? (ex-info parse-error {:reason reasons/reason-secret-failed}))

          (str/blank? (:name opts))
          (secret-error-result json? (ex-info (str "missing required argument <name>\n" secret-set-usage-line)
                                              {:reason reasons/reason-empty-secret-name}))

          :else
          (try
            (let [path (secret-vault-path ctx opts)
                  pp (resolve-passphrase! ctx opts)
                  value (read-secret-value ctx opts)
                  {:keys [name]} (vault-v2/set-secret! path pp (:name opts) value)]
              (if json?
                (secret-json-success {:action "set"
                                      :name name
                                      :type (:type opts)})
                (result {:exit-code 0
                         :stdout "ok\n"})))
            (catch Exception e
              (secret-error-result json? e))))))))

(defn- handle-secret-list
  [ctx args]
  (let [args (vec args)]
    (if (help-arg? (first args))
      (help-result "Usage: kimen secret list [--vault <path>] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json]\n")
      (let [[opts parse-error] (parse-secret-common-opts args)
            json? (:json? opts)]
        (cond
          parse-error
          (secret-error-result json? (ex-info parse-error {:reason reasons/reason-secret-failed}))

          (seq (:rest opts))
          (secret-error-result json? (ex-info "unexpected argument\nUsage: kimen secret list [--vault <path>] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json]"
                                              {:reason reasons/reason-secret-failed}))

          :else
          (try
            (let [path (secret-vault-path ctx opts)
                  pp (resolve-passphrase! ctx opts)
                  names (vault-v2/list-secret-names path pp)]
              (if json?
                (secret-json-success {:action "list"
                                      :names names})
                (result {:exit-code 0
                         :stdout (str (when (seq names) (str/join "\n" names)) (when (seq names) "\n"))})))
            (catch Exception e
              (secret-error-result json? e))))))))

(defn- handle-secret-get
  [ctx args]
  (let [args (vec args)]
    (if (help-arg? (first args))
      (help-result (str secret-get-usage-line "\n"))
      (let [[opts parse-error] (parse-secret-common-opts args)
            json? (:json? opts)
            names (:rest opts)]
        (cond
          parse-error
          (secret-error-result json? (ex-info parse-error {:reason reasons/reason-secret-failed}))

          (not= 1 (count names))
          (secret-error-result json? (ex-info (str "expected exactly one secret name\n" secret-get-usage-line)
                                              {:reason reasons/reason-empty-secret-name}))

          (not (:unsafe-stdout? opts))
          (secret-error-result json? (ex-info (str "refusing to print secrets; use --unsafe-stdout if you really want this\n" secret-get-usage-line)
                                              {:reason reasons/reason-unsafe-stdout-required}))

          :else
          (try
            (let [path (secret-vault-path ctx opts)
                  pp (resolve-passphrase! ctx opts)
                  sec (vault-v2/get-secret path pp (first names))]
              (if json?
                (let [value-b64 (.encodeToString (Base64/getEncoder) (.getBytes (:value sec) "UTF-8"))]
                  (secret-json-success {:action "get"
                                        :name (:name sec)
                                        :encoding "base64"
                                        :value_b64 value-b64}))
                (result {:exit-code 0
                         :stdout (:value sec)})))
            (catch Exception e
              (secret-error-result json? e))))))))

(defn- handle-secret-rm
  [ctx args]
  (let [args (vec args)]
    (if (help-arg? (first args))
      (help-result (str secret-rm-usage-line "\n"))
      (let [[opts parse-error] (parse-secret-common-opts args)
            json? (:json? opts)
            names (:rest opts)]
        (cond
          parse-error
          (secret-error-result json? (ex-info parse-error {:reason reasons/reason-secret-failed}))

          (not= 1 (count names))
          (secret-error-result json? (ex-info (str "expected exactly one secret name\n" secret-rm-usage-line)
                                              {:reason reasons/reason-empty-secret-name}))

          :else
          (try
            (let [path (secret-vault-path ctx opts)
                  pp (resolve-passphrase! ctx opts)
                  {:keys [name]} (vault-v2/delete-secret! path pp (first names))]
              (if json?
                (secret-json-success {:action "rm" :name name})
                (result {:exit-code 0 :stdout "ok\n"})))
            (catch Exception e
              (secret-error-result json? e))))))))

(defn- handle-secret-mv
  [ctx args]
  (let [args (vec args)]
    (if (help-arg? (first args))
      (help-result (str secret-mv-usage-line "\n"))
      (let [[opts parse-error] (parse-secret-common-opts args)
            json? (:json? opts)
            names (:rest opts)]
        (cond
          parse-error
          (secret-error-result json? (ex-info parse-error {:reason reasons/reason-secret-failed}))

          (not= 2 (count names))
          (secret-error-result json? (ex-info (str "expected <old-name> and <new-name>\n" secret-mv-usage-line)
                                              {:reason reasons/reason-empty-secret-name}))

          :else
          (try
            (let [path (secret-vault-path ctx opts)
                  pp (resolve-passphrase! ctx opts)
                  {:keys [from to]} (vault-v2/rename-secret! path pp (first names) (second names))]
              (if json?
                (secret-json-success {:action "mv" :from from :to to})
                (result {:exit-code 0 :stdout "ok\n"})))
            (catch Exception e
              (secret-error-result json? e))))))))

(defn- handle-secret
  [ctx args]
  (let [args (vec args)]
    (cond
      (or (empty? args) (help-arg? (first args)))
      (help-result secret-usage)

      (= "set" (first args)) (handle-secret-set ctx (rest args))
      (= "list" (first args)) (handle-secret-list ctx (rest args))
      (= "get" (first args)) (handle-secret-get ctx (rest args))
      (or (= "rm" (first args)) (= "delete" (first args))) (handle-secret-rm ctx (rest args))
      (= "mv" (first args)) (handle-secret-mv ctx (rest args))
      :else (error-with-help 1 (str "unknown secret command " (pr-str (first args))) secret-usage))))

(defn- projection-error-code
  [reason]
  (case reason
    "secret_not_found" exit-code/code-secret-not-found
    "vault_not_found" exit-code/code-vault-not-found
    "wrong_passphrase" exit-code/code-wrong-passphrase
    exit-code/code-projection-failed))

(defn- envfile-error-code
  [reason]
  (case reason
    "secret_not_found" exit-code/code-secret-not-found
    "vault_not_found" exit-code/code-vault-not-found
    "wrong_passphrase" exit-code/code-wrong-passphrase
    exit-code/code-envfile-failed))

(defn- projection-error-result
  [json? e]
  (let [reason (or (:reason (ex-data e)) reasons/reason-projection-failed)
        code (projection-error-code reason)]
    (if json?
      (error-json code reason (.getMessage e))
      (error-text code (.getMessage e)))))

(defn- envfile-error-result
  [json? e]
  (let [reason (or (:reason (ex-data e)) reasons/reason-envfile-failed)
        code (envfile-error-code reason)]
    (if json?
      (error-json code reason (.getMessage e))
      (error-text code (.getMessage e)))))

(defn- resolve-map-path!
  [opts missing-reason]
  (let [map-path (some-> (:map-path opts) str/trim not-empty)
        profile (some-> (:profile opts) str/trim not-empty)
        resolved-path
        (cond
          (and map-path profile)
          (throw (ex-info "conflicting --map and --profile inputs"
                          {:reason reasons/reason-conflicting-map-profile-inputs}))

          map-path
          map-path

          profile
          (mapfile/resolve-profile profile)

          :else
          nil)]
    (when (and (nil? resolved-path) (some? missing-reason))
      (throw (ex-info "missing --map or --profile" {:reason missing-reason})))
    resolved-path))

(defn- normalize-mapfile-reason
  [reason default-reason]
  (if (string? reason)
    reason
    (case reason
      :invalid-relative-path reasons/reason-invalid-relative-path
      :invalid-mapping reasons/reason-invalid-mapping
      :invalid-env-var reasons/reason-invalid-env-var
      default-reason)))

(defn- wrap-mapfile-ex
  [e default-reason]
  (let [reason (normalize-mapfile-reason (:reason (ex-data e)) default-reason)]
    (ex-info (.getMessage e) {:reason reason} e)))

(defn- parse-map-source!
  [source default-reason]
  (try
    (mapfile/parse-string source)
    (catch clojure.lang.ExceptionInfo e
      (throw (wrap-mapfile-ex e default-reason)))))

(defn- parse-inline-mappings!
  [opts default-reason]
  (try
    {:request (mapfile/parse-request (:env-mappings opts)
                                     (:file-mappings opts)
                                     (:stdin-spec opts))
     :env-paths (mapfile/parse-envpath-mappings (:envpath-mappings opts))}
    (catch clojure.lang.ExceptionInfo e
      (throw (wrap-mapfile-ex e default-reason)))))

(defn- resolve-mappings!
  [ctx opts default-reason]
  (let [resolved-path (resolve-map-path! opts nil)
        from-map
        (if resolved-path
          (parse-map-source! ((:read-file ctx) resolved-path) default-reason)
          {:request {:envs []
                     :files []
                     :stdin nil}
           :env-paths []})
        from-flags (parse-inline-mappings! opts default-reason)
        map-stdin (some-> (get-in from-map [:request :stdin]) str/trim not-empty)
        flag-stdin (some-> (get-in from-flags [:request :stdin]) str/trim not-empty)]
    (when (and map-stdin flag-stdin)
      (throw (ex-info "stdin projection specified multiple times (map/profile and flags)"
                      {:reason reasons/reason-conflicting-stdin-inputs})))
    {:request {:envs (vec (concat (get-in from-map [:request :envs])
                                  (get-in from-flags [:request :envs])))
               :files (vec (concat (get-in from-map [:request :files])
                                   (get-in from-flags [:request :files])))
               :stdin (or flag-stdin map-stdin)}
     :env-paths (vec (concat (:env-paths from-map)
                             (:env-paths from-flags)))}))

(defn- resolve-against-mappings!
  [ctx opts default-reason]
  (let [against-map (some-> (:against-map opts) str/trim not-empty)
        against-profile (some-> (:against-profile opts) str/trim not-empty)]
    (cond
      (and (nil? against-map) (nil? against-profile))
      {:against-request nil
       :against-env-paths nil
       :against-label nil}

      (and against-map against-profile)
      (throw (ex-info "use only one of --against-map or --against-profile"
                      {:reason reasons/reason-conflicting-against-inputs}))

      :else
      (let [{:keys [request env-paths]}
            (resolve-mappings! ctx {:map-path against-map
                                    :profile against-profile
                                    :env-mappings []
                                    :file-mappings []
                                    :envpath-mappings []
                                    :stdin-spec nil}
                               default-reason)
            label (if against-profile
                    (str "profile:" against-profile)
                    against-map)]
        {:against-request request
         :against-env-paths env-paths
         :against-label label}))))

(defn- make-secret-lookup
  [vault-path passphrase]
  (fn [secret-name]
    (try
      (:value (vault-v2/get-secret vault-path passphrase secret-name))
      (catch clojure.lang.ExceptionInfo e
        (if (= reasons/reason-secret-not-found (:reason (ex-data e)))
          (throw (ex-info (format "secret not found: %s" (pr-str secret-name))
                          (assoc (or (ex-data e) {})
                                 :reason reasons/reason-secret-not-found
                                 :secret_name secret-name)
                          e))
          (throw e))))))

(defn- validate-envpaths!
  [request env-paths files-dir reason-on-missing-files-dir]
  (when (and (seq env-paths) (empty? (:files request)))
    (throw (ex-info "envpath mappings require file projections"
                    {:reason reasons/reason-envpath-requires-projected-files})))
  (when (and reason-on-missing-files-dir (seq env-paths) (str/blank? files-dir))
    (throw (ex-info "missing files dir for envpath mappings"
                    {:reason reasons/reason-missing-files-dir-for-envpath})))
  (let [file-paths (set (map :rel-path (:files request)))]
    (doseq [{:keys [rel-path]} env-paths]
      (when-not (contains? file-paths rel-path)
        (throw (ex-info (format "envpath mapping points to missing file %s" (pr-str rel-path))
                        {:reason reasons/reason-envpath-missing-projected-file}))))))

(defn- runtime-temp-dir
  []
  (str (java.nio.file.Files/createTempDirectory "kimen-files-" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- build-systemd-render-hints
  [out-dir]
  [(str "Environment=KIMEN_FILES_DIR=" out-dir)
   (format "# files rendered under %s (dir 0700, files 0600)" out-dir)])

(defn- resolve-render-target!
  [opts]
  (let [service (some-> (:systemd-service opts) str/trim not-empty)
        out-dir (some-> (:out-dir opts) str/trim not-empty)
        print-hints? (:print-systemd-hints? opts)]
    (cond
      (and service out-dir)
      (throw (ex-info "use only one of --dir or --systemd-service"
                      {:reason reasons/reason-conflicting-render-target-inputs}))

      (and (nil? service) (nil? out-dir))
      (throw (ex-info "--dir is required (or use --systemd-service)"
                      {:reason reasons/reason-missing-render-target}))

      (and (nil? service) print-hints?)
      (throw (ex-info "--print-systemd-hints requires --systemd-service"
                      {:reason reasons/reason-systemd-hints-requires-service}))

      (and service (nil? (re-matches systemd-service-name-re service)))
      (throw (ex-info (format "invalid --systemd-service %s" (pr-str service))
                      {:reason reasons/reason-invalid-systemd-service}))

      :else
      (let [runtime-dir (or (some-> (:runtime-dir opts) str/trim not-empty) "/run")
            effective-out-dir (if service
                                (str (io/file runtime-dir "kimen" service))
                                out-dir)
            hints (when (and service print-hints?)
                    (build-systemd-render-hints effective-out-dir))]
        (assoc opts
               :out-dir effective-out-dir
               :systemd-service service
               :runtime-dir runtime-dir
               :hints hints)))))

(defn- handle-run
  [ctx args]
  (let [[opts parse-error] (parse-run-opts args)
        json? (:json? opts)]
    (cond
      parse-error
      (projection-error-result json? (ex-info parse-error {:reason reasons/reason-projection-failed}))

      :else
      (try
        (let [{:keys [request env-paths]} (resolve-mappings! ctx opts reasons/reason-projection-failed)]
          (if (:dry-run? opts)
            (let [payload (plan/plan-from-mappings {:request request
                                                    :env-paths env-paths
                                                    :mode "run"
                                                    :command (:command opts)})]
              (if json?
                (success-json payload)
                (result {:exit-code 0
                         :stdout (str (plan/render-plan-text payload) "\n")})))
            (let [_ (when (empty? (:command opts))
                      (throw (ex-info "missing command" {:reason reasons/reason-missing-command})))
                  vault-path (vault-path/resolve-vault-path ctx (:vault-path opts))
                  pp (resolve-passphrase! ctx opts)
                  lookup (make-secret-lookup vault-path pp)
                  requested-files-dir (some-> (:files-dir opts) str/trim not-empty)
                  auto-files-dir (when (and (seq (:files request)) (nil? requested-files-dir))
                                   (runtime-temp-dir))
                  files-dir (or requested-files-dir auto-files-dir)
                  _ (validate-envpaths! request env-paths files-dir false)
                  _ (when files-dir
                      (projection/render-files! {:lookup-secret lookup} files-dir (:files request)))
                  env-overrides (projection/env-overrides {:lookup-secret lookup
                                                           :files-dir files-dir}
                                                          request
                                                          env-paths)
                  stdin-value (projection/stdin-value {:lookup-secret lookup} request)
                  {:keys [exit out err]} (projection/run-child! (:command opts) env-overrides stdin-value)]
              (when (seq out)
                (print out)
                (flush))
              (when (seq err)
                (binding [*out* *err*]
                  (print err)
                  (flush)))
              (when auto-files-dir
                (projection/delete-dir-recursive! auto-files-dir))
              (result {:exit-code exit}))))
        (catch Exception e
          (projection-error-result json? e))))))
(defn- handle-render
  [ctx args]
  (let [[opts parse-error] (parse-render-opts args)
        json? (:json? opts)]
    (cond
      parse-error
      (projection-error-result json? (ex-info parse-error {:reason reasons/reason-projection-failed}))

      :else
      (try
        (let [opts (resolve-render-target! opts)
              out-dir (:out-dir opts)
              hints (:hints opts)
              {:keys [request env-paths]} (resolve-mappings! ctx opts reasons/reason-projection-failed)
              _ (when (some-> (:stdin request) str/trim not-empty)
                  (throw (ex-info "stdin projection is only supported for `kimen run`"
                                  {:reason reasons/reason-stdin-not-supported})))
              _ (validate-envpaths! request env-paths out-dir false)
              _ (when (empty? (:files request))
                  (throw (ex-info "no files to render" {:reason reasons/reason-no-files-to-render})))
              vault-path (vault-path/resolve-vault-path ctx (:vault-path opts))
              pp (resolve-passphrase! ctx opts)
              lookup (make-secret-lookup vault-path pp)
              n (projection/render-files! {:lookup-secret lookup} out-dir (:files request))]
          (if json?
            (success-json (cond-> {:ok true
                                   :action "render"
                                   :exit_code 0
                                   :out_dir out-dir
                                   :file_count n}
                            (seq hints) (assoc :hints hints)))
            (if (seq hints)
              (result {:exit-code 0
                       :stdout (str (str/join "\n" hints) "\n")})
              (result {:exit-code 0}))))
        (catch Exception e
          (projection-error-result json? e))))))

(defn- handle-envfile
  [ctx args]
  (let [[opts parse-error] (parse-envfile-opts args)
        json? (:json? opts)]
    (cond
      parse-error
      (envfile-error-result json? (ex-info parse-error {:reason reasons/reason-envfile-failed}))

      (str/blank? (:out-path opts))
      (envfile-error-result json? (ex-info "missing --out" {:reason reasons/reason-missing-out}))

      :else
      (try
        (let [{:keys [request env-paths]} (resolve-mappings! ctx opts reasons/reason-envfile-failed)
              _ (when (some-> (:stdin request) str/trim not-empty)
                  (throw (ex-info "stdin projection is only supported for `kimen run`"
                                  {:reason reasons/reason-stdin-not-supported})))
              _ (when (and (empty? (:envs request)) (empty? env-paths))
                  (throw (ex-info "missing env mappings" {:reason reasons/reason-missing-env-mappings})))
              _ (validate-envpaths! request env-paths (:files-dir opts) true)
              vault-path (vault-path/resolve-vault-path ctx (:vault-path opts))
              pp (resolve-passphrase! ctx opts)
              lookup (make-secret-lookup vault-path pp)
              env-map (projection/env-overrides {:lookup-secret lookup
                                                 :files-dir (:files-dir opts)}
                                                request
                                                env-paths)
              _ (projection/write-envfile! (:out-path opts) env-map)]
          (if json?
            (success-json {:ok true
                           :action "envfile"
                           :exit_code 0
                           :out (:out-path opts)
                           :count (count env-map)})
            (result {:exit-code 0
                     :stdout "ok\n"})))
        (catch Exception e
          (envfile-error-result json? e))))))

(defn- bundle-error-result
  [json? e]
  (let [reason (or (:reason (ex-data e)) reasons/reason-bundle-failed)]
    (if json?
      (error-json exit-code/code-bundle-failed reason (.getMessage e))
      (error-text exit-code/code-bundle-failed (.getMessage e)))))

(defn- resolve-identity!
  [ctx opts]
  (bundle/load-identity {:identity-file (:identity-file opts)
                         :from-stdin? (:identity-stdin? opts)
                         :stdin (:stdin ctx)}))

(defn- handle-bundle-keygen
  [args]
  (let [[opts parse-error] (parse-bundle-keygen-opts args)
        json? (:json? opts)]
    (cond
      parse-error
      (bundle-error-result json? (ex-info parse-error {:reason reasons/reason-bundle-failed}))

      (str/blank? (:out-path opts))
      (bundle-error-result json? (ex-info "--out is required" {:reason reasons/reason-missing-out}))

      :else
      (try
        (let [{:keys [recipient]} (bundle/generate-identity-file (:out-path opts) (:overwrite? opts))]
          (if json?
            (success-json {:ok true
                           :action "bundle_keygen"
                           :exit_code 0
                           :identity_path (:out-path opts)
                           :recipient recipient})
            (result {:exit-code 0
                     :stdout (when (:print-recipient? opts)
                               (str recipient "\n"))})))
        (catch Exception e
          (bundle-error-result json? e))))))

(defn- handle-bundle-recipient
  [ctx args]
  (let [[opts parse-error] (parse-bundle-recipient-opts args)
        json? (:json? opts)]
    (cond
      parse-error
      (bundle-error-result json? (ex-info parse-error {:reason reasons/reason-bundle-failed}))

      (and (str/blank? (:identity-file opts)) (not (:identity-stdin? opts)))
      (bundle-error-result json? (ex-info "provide --identity or --identity-stdin"
                                          {:reason reasons/reason-missing-identity-input}))

      :else
      (try
        (let [id (resolve-identity! ctx opts)
              recipient (bundle/recipient-for-identity id)]
          (if json?
            (success-json {:ok true
                           :action "bundle_recipient"
                           :exit_code 0
                           :recipient recipient})
            (result {:exit-code 0
                     :stdout (str recipient "\n")})))
        (catch Exception e
          (bundle-error-result json? e))))))

(defn- handle-bundle-seal
  [ctx args]
  (let [[opts parse-error] (parse-bundle-seal-opts args)
        json? (:json? opts)]
    (cond
      parse-error
      (bundle-error-result json? (ex-info parse-error {:reason reasons/reason-bundle-failed}))

      (str/blank? (:out-path opts))
      (bundle-error-result json? (ex-info "--out is required" {:reason reasons/reason-missing-out}))

      (empty? (:recipients opts))
      (bundle-error-result json? (ex-info "at least one --recipient is required"
                                          {:reason reasons/reason-missing-recipient}))

      :else
      (try
        (let [vault-path (vault-path/resolve-vault-path ctx (:vault-path opts))
              _ (bundle/seal-vault-file vault-path (:out-path opts) (:recipients opts))]
          (if json?
            (success-json {:ok true
                           :action "bundle_seal"
                           :exit_code 0
                           :vault vault-path
                           :out (:out-path opts)
                           :recipient_count (count (:recipients opts))})
            (result {:exit-code 0
                     :stdout (format "sealed %s -> %s\n" vault-path (:out-path opts))})))
        (catch Exception e
          (bundle-error-result json? e))))))

(defn- handle-bundle-open
  [ctx args]
  (let [[opts parse-error] (parse-bundle-open-opts args)
        json? (:json? opts)]
    (cond
      parse-error
      (bundle-error-result json? (ex-info parse-error {:reason reasons/reason-bundle-failed}))

      (str/blank? (:in-path opts))
      (bundle-error-result json? (ex-info "--in is required" {:reason reasons/reason-missing-in}))

      (and (str/blank? (:identity-file opts)) (not (:identity-stdin? opts)))
      (bundle-error-result json? (ex-info "provide --identity or --identity-stdin"
                                          {:reason reasons/reason-missing-identity-input}))

      :else
      (try
        (let [out-vault (or (some-> (:out-vault opts) str/trim not-empty)
                            (vault-path/default-vault-path))
              id (resolve-identity! ctx opts)
              _ (bundle/open-to-vault-file (:in-path opts) out-vault id (:overwrite? opts))]
          (if json?
            (success-json {:ok true
                           :action "bundle_open"
                           :exit_code 0
                           :in (:in-path opts)
                           :out_vault out-vault})
            (result {:exit-code 0
                     :stdout (format "opened %s -> %s\n" (:in-path opts) out-vault)})))
        (catch Exception e
          (bundle-error-result json? e))))))

(defn- handle-bundle
  [ctx args]
  (let [args (vec args)]
    (cond
      (or (empty? args) (help-arg? (first args)))
      (help-result bundle-usage)

      (= "keygen" (first args)) (handle-bundle-keygen (rest args))
      (= "recipient" (first args)) (handle-bundle-recipient ctx (rest args))
      (= "seal" (first args)) (handle-bundle-seal ctx (rest args))
      (= "open" (first args)) (handle-bundle-open ctx (rest args))
      :else (error-with-help 1 (str "unknown bundle command " (pr-str (first args))) bundle-usage))))

(defn- doctor-error-result
  [json? e]
  (let [reason (or (:reason (ex-data e)) reasons/reason-doctor-failed)]
    (if json?
      (error-json exit-code/code-doctor-failed reason (.getMessage e))
      (error-text exit-code/code-doctor-failed (.getMessage e)))))

(defn- init-error-result
  [json? e]
  (let [reason (or (:reason (ex-data e)) reasons/reason-init-failed)]
    (if json?
      (error-json exit-code/code-init-failed reason (.getMessage e))
      (error-text exit-code/code-init-failed (.getMessage e)))))

(defn- handle-doctor
  [ctx args]
  (let [[opts parse-error] (parse-doctor-opts args)
        json? (:json? opts)]
    (cond
      parse-error
      (doctor-error-result json? (ex-info parse-error {:reason reasons/reason-doctor-failed}))

      :else
      (try
        (let [report (-> (doctor/run-doctor-checks {:ctx ctx
                                                    :map-path (:map-path opts)
                                                    :profile (:profile opts)
                                                    :bundle-in (:bundle-in opts)
                                                    :identity (:identity opts)
                                                    :allow-missing-vault? (:allow-missing-vault? opts)})
                         (doctor/finalize-report (:strict? opts)))
              exit-code (:exit_code report)]
          (if json?
            (result {:exit-code exit-code
                     :stdout (json-line report)})
            (result {:exit-code exit-code
                     :stdout (str (doctor/render-report-text report) "\n")})))
        (catch Exception e
          (doctor-error-result json? e))))))

(defn- handle-init-ci-pr-safety
  [args]
  (let [[opts parse-error] (parse-init-ci-pr-safety-opts args)
        json? (:json? opts)]
    (cond
      parse-error
      (init-error-result json? (ex-info parse-error {:reason reasons/reason-init-failed}))

      :else
      (try
        (let [payload (init/init-ci-pr-safety! opts)]
          (if json?
            (success-json payload)
            (result {:exit-code 0
                     :stdout (format "ok (%s)\n" (:out payload))})))
        (catch Exception e
          (init-error-result json? e))))))

(defn- handle-init-ci-deploy
  [args]
  (let [[opts parse-error] (parse-init-ci-deploy-opts args)
        json? (:json? opts)]
    (cond
      parse-error
      (init-error-result json? (ex-info parse-error {:reason reasons/reason-init-failed}))

      :else
      (try
        (let [payload (init/init-ci-deploy! opts)]
          (if json?
            (success-json payload)
            (result {:exit-code 0
                     :stdout (format "ok (%s)\n" (:out payload))})))
        (catch Exception e
          (init-error-result json? e))))))

(defn- handle-init-ci-sync-gate
  [args]
  (let [[opts parse-error] (parse-init-ci-sync-gate-opts args)
        json? (:json? opts)]
    (cond
      parse-error
      (init-error-result json? (ex-info parse-error {:reason reasons/reason-init-failed}))

      :else
      (try
        (let [payload (init/init-ci-sync-gate! opts)]
          (if json?
            (success-json payload)
            (result {:exit-code 0
                     :stdout (format "ok (%s)\n" (:out payload))})))
        (catch Exception e
          (init-error-result json? e))))))

(defn- handle-init
  [args]
  (let [args (vec args)]
    (cond
      (or (empty? args) (help-arg? (first args)))
      (help-result init-usage)

      (= "ci-pr-safety" (first args)) (handle-init-ci-pr-safety (rest args))
      (= "ci-deploy" (first args)) (handle-init-ci-deploy (rest args))
      (= "ci-sync-gate" (first args)) (handle-init-ci-sync-gate (rest args))
      :else (error-with-help 1 (str "unknown init command " (pr-str (first args))) init-usage))))

(defn- handle-project
  [ctx args]
  (let [args (vec args)]
    (cond
      (empty? args)
      (help-result project-usage)

      (help-arg? (first args))
      (help-result project-usage)

      (= "run" (first args))
      (if (help-arg? (second args))
        (help-result "Usage: kimen project run [--map <path>|--profile <name>] [--env VAR=<value>] [--file relpath=<value>] [--envpath VAR=relpath] [--stdin <value>] [--files-dir <path>] [--json] [--dry-run] [-- <command> [args...]]\n")
        (handle-run ctx (rest args)))

      (= "render" (first args))
      (if (help-arg? (second args))
        (help-result "Usage: kimen project render [--map <path>|--profile <name>] [--file relpath=<value>] (--dir <path>|--systemd-service <name>) [--runtime-dir <path>] [--print-systemd-hints] [--json]\n")
        (handle-render ctx (rest args)))

      (= "plan" (first args))
      (if (help-arg? (second args))
        (help-result "Usage: kimen project plan [--map <path>|--profile <name>] [--env VAR=<value>] [--file relpath=<value>] [--envpath VAR=relpath] [--stdin <value>] [--against-map <path>|--against-profile <name>] [--mode run|render|envfile] [--json] [-- <command> [args...]]\n")
        (handle-plan ctx (rest args)))

      :else
      (error-with-help 1 (str "unknown project command " (pr-str (first args))) project-usage))))

(defn- handle-completion
  [args]
  (let [args (vec args)]
    (cond
      (empty? args)
      (error-with-help 1
                       (str "missing required argument <shell> (expected one of: "
                            (completion/supported-shells-str)
                            ")")
                       completion/usage)

      (help-arg? (first args))
      (help-result completion/usage)

      (> (count args) 1)
      (error-with-help 1 (str "unexpected arguments " (pr-str (subvec args 1))) completion/usage)

      :else
      (let [shell (-> (first args) str str/trim str/lower-case)
            script (completion/script-for-shell shell)]
        (if (nil? script)
          (error-with-help 1
                           (str "unsupported shell " (pr-str shell)
                                " (expected one of: "
                                (completion/supported-shells-str)
                                ")")
                           completion/usage)
          (result {:exit-code 0
                   :stdout script}))))))

(defn run
  [ctx argv]
  (let [raw-args (vec argv)
        args (if (and (seq raw-args) (= "--" (first raw-args)))
               (subvec raw-args 1)
               raw-args)]
    (cond
      (empty? args)
      (help-result usage)

      (and (help-arg? (first args))
           (empty? (rest args)))
      (help-result usage)

      (help-arg? (first args))
      (help-result (command-help (first (rest args))))

      (= "version" (first args))
      (if (help-arg? (second args))
        (help-result (command-help "version"))
        (handle-version (rest args)))

      (= "config" (first args))
      (handle-config ctx (rest args))

      (= "vault" (first args))
      (handle-vault ctx (rest args))

      (= "secret" (first args))
      (handle-secret ctx (rest args))

      (= "run" (first args))
      (if (help-arg? (second args))
        (help-result run-usage)
        (handle-run ctx (rest args)))

      (= "render" (first args))
      (if (help-arg? (second args))
        (help-result render-usage)
        (handle-render ctx (rest args)))

      (= "envfile" (first args))
      (if (help-arg? (second args))
        (help-result envfile-usage)
        (handle-envfile ctx (rest args)))

      (= "bundle" (first args))
      (handle-bundle ctx (rest args))

      (= "doctor" (first args))
      (if (help-arg? (second args))
        (help-result doctor-usage)
        (handle-doctor ctx (rest args)))

      (= "init" (first args))
      (handle-init (rest args))

      (= "remote" (first args))
      (handle-remote ctx (rest args))

      (= "sync" (first args))
      (handle-sync ctx (rest args))

      (= "map" (first args))
      (handle-map ctx (rest args))

      (= "plan" (first args))
      (if (help-arg? (second args))
        (help-result plan-usage)
        (handle-plan ctx (rest args)))

      (= "project" (first args))
      (handle-project ctx (rest args))

      (= "completion" (first args))
      (handle-completion (rest args))

      :else
      (error-with-help 1 (str "unknown command " (pr-str (first args))) usage))))

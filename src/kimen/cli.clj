(ns kimen.cli
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [kimen.bundle :as bundle]
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
   [kimen.vault-path :as vault-path]
   [kimen.vault.v2 :as vault-v2])
  (:import
   [java.security MessageDigest]
   [java.nio.file Files StandardCopyOption]
   [java.util Base64]))

(def usage
  (str/join
   "\n"
   ["kimen (clojure rewrite, early preview)"
    ""
    "usage:"
    "  kimen version [--json]"
    "  kimen config path [--json]"
    "  kimen config show [--pretty=false]"
    "  kimen config vault set <vault-path> [--json]"
    "  kimen config vault show [--json]"
    "  kimen config vault clear [--json]"
    "  kimen config unlock set <prompt|env|stdin|exec> [-- <command> [args...]] [--json]"
    "  kimen config unlock show [--json]"
    "  kimen config unlock clear [--json]"
    "  kimen vault init [--vault <path>] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json]"
    "  kimen vault info [--vault <path>] [--json]"
    "  kimen vault path [--vault <path>] [--json]"
    "  kimen secret set <name> [--stdin|--value <text>] [--vault <path>] [--json]"
    "  kimen secret list [--vault <path>] [--json]"
    "  kimen secret get <name> --unsafe-stdout [--vault <path>] [--json]"
    "  kimen secret rm <name> [--vault <path>] [--json]"
    "  kimen secret mv <old-name> <new-name> [--vault <path>] [--json]"
    "  kimen bundle keygen --out <path> [--overwrite] [--print-recipient] [--json]"
    "  kimen bundle recipient (--identity <path>|--identity-stdin) [--json]"
    "  kimen bundle seal [--vault <path>] --out <path> --recipient <age1...> [--recipient <age1...> ...] [--json]"
    "  kimen bundle open --in <path> [--out-vault <path>] (--identity <path>|--identity-stdin) [--overwrite] [--json]"
    "  kimen remote add <name> --path <path> [--type fs|git] [--recipient <age1...>] [--identity <path>] [--branch <name>] [--bundle-path <path>] [--derive-recipient|--no-derive-recipient] [--json]"
    "  kimen remote get <name> [--json]"
    "  kimen remote set <name> [--type fs|git] [--path <path>] [--recipient <age1...>] [--identity <path>] [--branch <name>] [--bundle-path <path>] [--derive-recipient|--no-derive-recipient] [--json]"
    "  kimen remote list [--json]"
    "  kimen remote rm <name> [--json]"
    "  kimen sync init [name] [--remote <name>] [--type fs|git] [--path <path>] [--recipient <age1...>] [--identity <path>] [--branch <name>] [--bundle-path <path>] [--update] [--no-check] [--json]"
    "  kimen sync preflight [--remote <name>] [--profile <name>] [--bundle-in <path>] [--identity <path>] [--stale-threshold <dur>] [--strict] [--allow-missing-vault] [--only <check>] [--skip <check>] [--json]"
    "  kimen sync status [--remote <name>] [--stale-threshold <dur>] [--strict] [--terse] [--json]"
    "  kimen sync conflicts [--remote <name>] [--stale-threshold <dur>] [--strict] [--terse] [--json]"
    "  kimen sync push [--remote <name>] [--dry-run] [--force] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json]"
    "  kimen sync pull [--remote <name>] [--dry-run] [--reconcile] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json]"
    "  kimen sync changes [--remote <name>] [--terse] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json]"
    "  kimen sync resolve [--remote <name>] --take local|remote [--key <name>] [--key <name> ...] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json]"
    "  kimen sync [--remote <name>] [--dry-run] [--force] [--reconcile] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json]"
    "  kimen sync reset-baseline [--remote <name>] (--clear|--to-remote|--rev <sha256>) --yes [--passphrase-stdin|--passphrase-cmd <cmd>] [--json]"
    "  kimen sync unlock [--remote <name>] [--if-older-than <dur>] --yes [--json]"
    "  kimen sync restore --backup <path> [--no-backup] [--json]"
    "  kimen run [--map <path>|--profile <name>] [--env VAR=<value>] [--file relpath=<value>] [--envpath VAR=relpath] [--stdin <value>] [--files-dir <path>] [--json] [--dry-run] [-- <command> [args...]]"
    "  kimen render [--map <path>|--profile <name>] [--file relpath=<value>] --dir <path> [--json]"
    "  kimen envfile [--map <path>|--profile <name>] [--env VAR=<value>] [--file relpath=<value>] [--envpath VAR=relpath] --out <path> [--files-dir <path>] [--json]"
    "  kimen map lint [--map <path>|--profile <name>] [--mode all|run|render|envfile] [--strict] [--json]"
    "  kimen plan [--map <path>|--profile <name>] [--env VAR=<value>] [--file relpath=<value>] [--envpath VAR=relpath] [--stdin <value>] [--against-map <path>|--against-profile <name>] [--mode run|render|envfile] [--json] [-- <command> [args...]]"
    "  kimen project run [--map <path>|--profile <name>] [--env VAR=<value>] [--file relpath=<value>] [--envpath VAR=relpath] [--stdin <value>] [--files-dir <path>] [--json] [--dry-run] [-- <command> [args...]]"
    "  kimen project render [--map <path>|--profile <name>] [--file relpath=<value>] --dir <path> [--json]"
    "  kimen project plan [--map <path>|--profile <name>] [--env VAR=<value>] [--file relpath=<value>] [--envpath VAR=relpath] [--stdin <value>] [--against-map <path>|--against-profile <name>] [--mode run|render|envfile] [--json] [-- <command> [args...]]"
    "  kimen doctor [--map <path>|--profile <name>] [--bundle-in <path>] [--identity <path>] [--strict] [--allow-missing-vault] [--json]"
    "  kimen init ci-pr-safety [--out <path>] [--force] [--profile <name>] [--command <cmd>] [--json]"
    "  kimen init ci-deploy [--out <path>] [--force] [--profile <name>] [--deploy-command <cmd>] [--json]"
    "  kimen init ci-sync-gate [--out <path>] [--force] [--remote-name <name>] [--remote-type git|fs] [--remote-path <path>] [--remote-branch <name>] [--remote-bundle-path <path>] [--local-bundle <path>] [--profile <name>] [--stale-threshold <dur>] [--json]"
    ""]))

(def project-usage
  (str/join
   "\n"
   ["kimen project"
    ""
    "usage:"
    "  kimen project run [--map <path>|--profile <name>] [--env VAR=<value>] [--file relpath=<value>] [--envpath VAR=relpath] [--stdin <value>] [--files-dir <path>] [--json] [--dry-run] [-- <command> [args...]]"
    "  kimen project render [--map <path>|--profile <name>] [--file relpath=<value>] --dir <path> [--json]"
    "  kimen project plan [--map <path>|--profile <name>] [--env VAR=<value>] [--file relpath=<value>] [--envpath VAR=relpath] [--stdin <value>] [--against-map <path>|--against-profile <name>] [--mode run|render|envfile] [--json] [-- <command> [args...]]"
    ""]))

(def remote-name-re #"^[A-Za-z0-9_.-]+$")
(def env-remote-name "KIMEN_REMOTE")

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
    (let [v (read-secret-value-from-stdin ctx)]
      (when (str/blank? v)
        (throw (ex-info "empty secret value" {:reason reasons/reason-empty-secret-value})))
      v)

    (some? value)
    (let [v (str value)]
      (when (str/blank? v)
        (throw (ex-info "empty secret value" {:reason reasons/reason-empty-secret-value})))
      v)

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
              payload (plan/plan-from-mappings {:request request
                                                :env-paths env-paths
                                                :mode (:mode opts)
                                                :command (:command opts)
                                                :against-request against-request
                                                :against-env-paths against-env-paths
                                                :against-label against-label})]
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
      (error-text 1 "unknown config command"))))

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
      (= "add" (first args)) (handle-remote-add ctx (rest args))
      (= "get" (first args)) (handle-remote-get ctx (rest args))
      (= "set" (first args)) (handle-remote-set ctx (rest args))
      (= "list" (first args)) (handle-remote-list ctx (rest args))
      (or (= "rm" (first args))
          (= "remove" (first args))
          (= "delete" (first args))) (handle-remote-rm ctx (rest args))
      :else (error-text 1 "unknown remote command"))))

(defn- sync-error-result
  [json? e]
  (let [reason (or (:reason (ex-data e)) reasons/reason-sync-failed)]
    (if json?
      (error-json exit-code/code-sync-failed reason (.getMessage e))
      (error-text exit-code/code-sync-failed (.getMessage e)))))

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

(defn- hex-bytes
  [^bytes b]
  (apply str (map #(format "%02x" (bit-and % 0xff)) b)))

(defn- file-sha256-hex
  [path]
  (let [bytes (Files/readAllBytes (.toPath (io/file path)))
        digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (hex-bytes digest)))

(defn- ensure-fs-remote!
  [remote]
  (let [remote-type (or (some-> (get remote "type") str/trim not-empty) "fs")]
    (when-not (= remote-type "fs")
      (throw (ex-info "only fs remotes are supported by this command for now"
                      {:reason reasons/reason-unsupported-remote-type})))
    remote))

(defn- remote-lock-path
  [remote]
  (when-let [bundle-path (fs-remote-bundle-path remote)]
    (str bundle-path ".lock")))

(defn- copy-file!
  [src dst]
  (Files/copy (.toPath (io/file src))
              (.toPath (io/file dst))
              (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
  dst)

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
  (let [bundle-path (fs-remote-bundle-path remote)
        identity-path (some-> (get remote "identity") str/trim not-empty)
        _ (when (str/blank? bundle-path)
            (throw (ex-info "remote bundle path is empty" {:reason reasons/reason-missing-path})))
        _ (when-not (.exists (io/file bundle-path))
            (throw (ex-info (format "remote bundle missing: %s" bundle-path)
                            {:reason reasons/reason-remote-bundle-missing})))
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
                          {:reason reasons/reason-resolve-key-not-conflict})))
        selected))))

(defn- select-sync-remote!
  [ctx remote-opt]
  (let [remotes (config/config-remote-list (:config-path ctx))
        requested (or (some-> remote-opt str/trim not-empty)
                      (some-> (System/getenv env-remote-name) str/trim not-empty))]
    (when (empty? remotes)
      (throw (ex-info "no remotes configured" {:reason reasons/reason-no-remotes-configured})))
    (cond
      requested
      (or (some #(when (= requested (get % "name")) %) remotes)
          (throw (ex-info (format "remote %s not found" (pr-str requested))
                          {:reason reasons/reason-remote-not-found})))

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
                      {:reason reasons/reason-unknown-preflight-check})))))

(defn- sync-status-payload
  ([ctx remote]
   (sync-status-payload ctx remote {}))
  ([ctx remote {:keys [stale-threshold-ms]}]
   (let [remote-name (get remote "name")
         remote-type (or (some-> (get remote "type") str/trim not-empty) "fs")
         remote-path (some-> (get remote "path") str/trim)
         bundle-path (when (= remote-type "fs") (fs-remote-bundle-path remote))
         lock-path (when bundle-path (str bundle-path ".lock"))
         lock-file (when lock-path (io/file lock-path))
         has-remote (boolean (and bundle-path (.exists (io/file bundle-path))))
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
         remote-rev (when has-remote (file-sha256-hex bundle-path))
         last-seen (some-> sync-entry (get "last_seen_rev") str/trim not-empty)
         local-hash (when has-local (file-sha256-hex vault-path))
         last-local-hash (some-> sync-entry (get "local_hash") str/trim not-empty)
         remote-changed (boolean (and has-remote last-seen remote-rev (not= last-seen remote-rev)))
         local-changed (boolean (and has-local last-local-hash local-hash (not= last-local-hash local-hash)))
         has-conflict (boolean (and remote-changed local-changed))
         blockers (cond-> []
                    (not has-local) (conj "local_vault_missing")
                    (and has-local (nil? recipient)) (conj "remote_recipient_missing")
                    (and has-remote (not has-local) (nil? identity)) (conj "remote_identity_missing")
                    has-lock (conj "remote_lock_present"))
         can-push (boolean (and has-local (not has-lock) (some? recipient)))
         needs-pull (boolean (and has-remote (not has-local)))
         in-sync (boolean (and has-remote has-local last-seen remote-rev (= last-seen remote-rev) (not local-changed)))
         recommended-action
         (cond
           has-conflict "sync_pull_reconcile"
           has-lock "wait_or_sync_unlock"
           remote-changed "sync_pull"
           local-changed "sync_push"
           (and has-local (nil? recipient)) "configure_remote_recipient"
           (and has-remote (not has-local) (nil? identity)) "configure_remote_identity"
           (and has-remote (not has-local)) "sync_pull"
           has-local "sync_push"
           :else "vault_init")]
     {:ok true
      :action "sync_status"
      :exit_code 0
      :remote remote-name
      :remote_type remote-type
      :remote_path remote-path
      :bundle_path bundle-path
      :vault_path vault-path
      :remote_rev remote-rev
      :last_seen_rev last-seen
      :local_hash local-hash
      :last_local_hash last-local-hash
      :has_remote has-remote
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
      :blockers blockers
      :recommended_action recommended-action})))

(defn- sync-status-strict-error
  [payload]
  (let [blockers (vec (:blockers payload))]
    (cond
      (:has_conflict payload)
      (ex-info "sync status indicates conflict"
               {:reason reasons/reason-overlapping-changes})

      (:has_lock payload)
      (ex-info "remote lock present"
               {:reason reasons/reason-remote-lock-present})

      (not (:has_local payload))
      (ex-info "local vault missing"
               {:reason reasons/reason-local-vault-missing})

      (some #{"remote_recipient_missing"} blockers)
      (ex-info "remote recipient is not configured (set --recipient on `remote add`)"
               {:reason reasons/reason-remote-recipient-missing})

      (some #{"remote_identity_missing"} blockers)
      (ex-info "remote identity is not configured (set --identity on `remote add`)"
               {:reason reasons/reason-remote-identity-missing})

      (not (:can_push payload))
      (ex-info (format "sync status indicates push is blocked (blockers=%s)"
                       (if (seq blockers) (str/join "," blockers) "none"))
               {:reason reasons/reason-sync-failed})

      :else
      nil)))

(defn- handle-sync-status
  [ctx args]
  (let [[opts parse-error] (parse-sync-status-opts args)
        json? (:json? opts)]
    (if parse-error
      (sync-error-result json? (ex-info parse-error {:reason reasons/reason-sync-failed}))
      (try
        (when (neg? (:stale-threshold-ms opts))
          (throw (ex-info "--stale-threshold must be >= 0"
                          {:reason reasons/reason-sync-failed})))
        (let [remote (select-sync-remote! ctx (:remote opts))
              payload (sync-status-payload ctx remote opts)]
          (when-let [e (when (:strict? opts)
                         (sync-status-strict-error payload))]
            (throw e))
          (if json?
            (success-json payload)
            (result {:exit-code 0
                     :stdout (format "remote=%s in_sync=%s can_push=%s needs_pull=%s has_lock=%s blockers=%s recommended_action=%s\n"
                                     (:remote payload)
                                     (:in_sync payload)
                                     (:can_push payload)
                                     (:needs_pull payload)
                                     (:has_lock payload)
                                     (if (seq (:blockers payload))
                                       (str/join "," (:blockers payload))
                                       "-")
                                     (:recommended_action payload))})))
        (catch Exception e
          (sync-error-result json? e))))))

(defn- sync-conflicts-strict-error
  [payload]
  (cond
    (:has_conflict payload)
    (ex-info "sync conflicts indicates conflict"
             {:reason reasons/reason-overlapping-changes})

    (:has_lock payload)
    (ex-info "remote lock present"
             {:reason reasons/reason-remote-lock-present})

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
                          {:reason reasons/reason-sync-failed})))
        (let [remote (select-sync-remote! ctx (:remote opts))
              status (sync-status-payload ctx remote opts)
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
                       :remote_changed (:remote_changed status)
                       :local_changed (:local_changed status)
                       :has_conflict (:has_conflict status)
                       :lock_blocks_push (:lock_blocks_push status)
                       :likely_stale (:likely_stale status)
                       :lock_age_seconds (:lock_age_seconds status)
                       :blockers (:blockers status)
                       :recommended_action (:recommended_action status)}]
          (when-let [e (when (:strict? opts)
                         (sync-conflicts-strict-error payload))]
            (throw e))
          (if json?
            (success-json payload)
            (if (:terse? opts)
              (result {:exit-code 0
                       :stdout (format "remote=%s has_conflict=%s remote_changed=%s local_changed=%s recommended_action=%s\n"
                                       (:remote payload)
                                       (:has_conflict payload)
                                       (:remote_changed payload)
                                       (:local_changed payload)
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
        bundle-path (fs-remote-bundle-path remote)
        has-remote (boolean (and bundle-path (.exists (io/file bundle-path))))
        vault-path (vault-path/resolve-vault-path ctx nil)
        has-local (boolean (.exists (io/file vault-path)))
        remote-rev (when has-remote (file-sha256-hex bundle-path))
        sync-entry (config/config-sync-entry (:config-path ctx) remote-name)
        baseline (normalize-hash-map (get sync-entry "baseline_secret_hashes"))
        passphrase (when (or has-local has-remote)
                     (resolve-passphrase! ctx opts))
        local-snap (if has-local
                     (load-vault-snapshot vault-path passphrase false)
                     {:hashes {}})
        remote-snap (if has-remote
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
     :remote_rev remote-rev
     :has_remote has-remote
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
        (let [remote (-> (select-sync-remote! ctx (:remote opts))
                         ensure-fs-remote!)
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
                            {:reason reasons/reason-overlapping-changes}))

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
        (let [remote (-> (select-sync-remote! ctx (:remote opts))
                         ensure-fs-remote!)
              remote-name (get remote "name")
              bundle-path (fs-remote-bundle-path remote)
              _ (when (str/blank? bundle-path)
                  (throw (ex-info "remote bundle path is empty" {:reason reasons/reason-missing-path})))
              _ (when-not (.exists (io/file bundle-path))
                  (throw (ex-info (format "remote bundle missing: %s" bundle-path)
                                  {:reason reasons/reason-remote-bundle-missing})))
              remote-rev (file-sha256-hex bundle-path)
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
                  (throw (ex-info "cannot resolve conflicts without baseline key hashes; run `kimen sync changes` after a clean sync first"
                                  {:reason reasons/reason-no-local-baseline})))
              analysis (analyze-sync-changes baseline (:hashes local-snap) (:hashes remote-snap))
              _ (when (empty? (:conflict-keys analysis))
                  (throw (ex-info "no overlapping key conflicts to resolve"
                                  {:reason reasons/reason-no-overlapping-conflicts})))
              selected-keys (resolve-sync-selected-keys! (:keys opts) (:conflict-keys analysis))
              _ (when (= take "remote")
                  (apply-sync-resolve-remote! vault-path passphrase selected-keys (:secrets remote-snap)))
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
              local-hash (file-sha256-hex vault-path)
              next-entry (cond-> (merge (or sync-entry {})
                                        {"local_hash" local-hash
                                         "baseline_secret_hashes" baseline-resolved})
                           (empty? baseline-resolved) (dissoc "baseline_secret_hashes")
                           (empty? (:remote-changed-keys remaining)) (assoc "last_seen_rev" remote-rev))
              saved-entry (config/config-sync-replace! (:config-path ctx) remote-name next-entry)
              last-seen (some-> saved-entry (get "last_seen_rev") str/trim not-empty)
              payload {:ok true
                       :action "sync_resolve"
                       :exit_code 0
                       :remote remote-name
                       :remote_rev remote-rev
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

      :else
      (try
        (let [remote (-> (select-sync-remote! ctx (:remote opts))
                         ensure-fs-remote!)
              remote-name (get remote "name")
              bundle-path (fs-remote-bundle-path remote)
              lock-path (remote-lock-path remote)
              recipient (some-> (get remote "recipient") str/trim not-empty)
              vault-path (vault-path/resolve-vault-path ctx nil)
              has-local (boolean (.exists (io/file vault-path)))
              sync-entry (config/config-sync-entry (:config-path ctx) remote-name)
              last-seen (some-> sync-entry (get "last_seen_rev") str/trim not-empty)
              remote-exists? (boolean (and (not (str/blank? bundle-path))
                                           (.exists (io/file bundle-path))))
              remote-rev-before (when remote-exists? (file-sha256-hex bundle-path))
              force? (boolean (:force? opts))
              _ (when (str/blank? bundle-path)
                  (throw (ex-info "remote bundle path is empty" {:reason reasons/reason-missing-path})))
              _ (when (.exists (io/file lock-path))
                  (throw (ex-info "remote lock present" {:reason reasons/reason-remote-lock-present})))
              _ (when-not has-local
                  (throw (ex-info (format "local vault missing: %s" vault-path)
                                  {:reason reasons/reason-local-vault-missing})))
              _ (when (nil? recipient)
                  (throw (ex-info "remote recipient is not configured (set --recipient on `remote add`)"
                                  {:reason reasons/reason-remote-recipient-missing})))
              _ (when (and (not force?) remote-exists? (nil? last-seen))
                  (throw (ex-info "remote has data but no local baseline; run sync pull first"
                                  {:reason reasons/reason-no-local-baseline})))
              _ (when (and (not force?) remote-exists? last-seen remote-rev-before (not= last-seen remote-rev-before))
                  (throw (ex-info "remote changed since last baseline; run sync pull"
                                  {:reason reasons/reason-remote-changed})))]
          (if (:dry-run? opts)
            (let [payload {:ok true
                           :action "sync_push"
                           :exit_code 0
                           :remote remote-name
                           :remote_type "fs"
                           :remote_path (get remote "path")
                           :bundle_path bundle-path
                           :vault_path vault-path
                           :dry_run true
                           :forced force?
                           :has_local has-local
                           :can_push true}]
              (if json?
                (success-json payload)
                (result {:exit-code 0
                         :stdout (format "dry-run: would push %s -> %s\n" vault-path bundle-path)})))
            (do
              (bundle/seal-vault-file vault-path bundle-path [recipient])
              (let [remote-rev (file-sha256-hex bundle-path)
                    local-hash (file-sha256-hex vault-path)
                    sync-passphrase (maybe-sync-passphrase ctx opts)
                    baseline-secret-hashes (when sync-passphrase
                                             (:hashes (load-vault-snapshot vault-path sync-passphrase false)))
                    _ (config/config-sync-mark-seen! (:config-path ctx) remote-name remote-rev local-hash baseline-secret-hashes)
                    payload {:ok true
                             :action "sync_push"
                             :exit_code 0
                             :remote remote-name
                             :remote_type "fs"
                             :remote_path (get remote "path")
                             :bundle_path bundle-path
                             :vault_path vault-path
                             :remote_rev remote-rev
                             :last_seen_rev remote-rev
                             :local_hash local-hash
                             :has_baseline_hashes (boolean baseline-secret-hashes)
                             :forced force?
                             :has_local true
                             :can_push true}]
                (if json?
                  (success-json payload)
                  (result {:exit-code 0
                           :stdout (format "ok (pushed %s)\n" remote-name)}))))))
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

      :else
      (try
        (let [remote (-> (select-sync-remote! ctx (:remote opts))
                         ensure-fs-remote!)
              remote-name (get remote "name")
              bundle-path (fs-remote-bundle-path remote)
              identity-path (some-> (get remote "identity") str/trim not-empty)
              vault-path (vault-path/resolve-vault-path ctx nil)
              local-exists? (.exists (io/file vault-path))
              _ (when (str/blank? bundle-path)
                  (throw (ex-info "remote bundle path is empty" {:reason reasons/reason-missing-path})))
              _ (when-not (.exists (io/file bundle-path))
                  (throw (ex-info (format "remote bundle missing: %s" bundle-path)
                                  {:reason reasons/reason-remote-bundle-missing})))
              _ (when (nil? identity-path)
                  (throw (ex-info "remote identity is not configured (set --identity on `remote add`)"
                                  {:reason reasons/reason-remote-identity-missing})))
              identity (bundle/load-identity {:identity-file identity-path
                                              :from-stdin? false
                                              :stdin nil})
              remote-rev (file-sha256-hex bundle-path)
              sync-entry (config/config-sync-entry (:config-path ctx) remote-name)
              baseline-secret-hashes-in (normalize-hash-map (get sync-entry "baseline_secret_hashes"))
              last-seen (some-> sync-entry (get "last_seen_rev") str/trim not-empty)
              local-hash-before (when local-exists? (file-sha256-hex vault-path))
              last-local-hash (some-> sync-entry (get "local_hash") str/trim not-empty)
              remote-changed (boolean (and last-seen remote-rev (not= last-seen remote-rev)))
              local-changed (boolean (and local-exists? last-local-hash local-hash-before (not= last-local-hash local-hash-before)))
              has-conflict (boolean (and remote-changed local-changed))
              reconcile? (boolean (:reconcile? opts))
              _ (when (and has-conflict (not reconcile?))
                  (throw (ex-info "local and remote have overlapping changes; rerun with --reconcile"
                                  {:reason reasons/reason-overlapping-changes})))
              _ (when (and reconcile? has-conflict (nil? baseline-secret-hashes-in))
                  (throw (ex-info "cannot reconcile without baseline key hashes; run sync changes after a clean sync"
                                  {:reason reasons/reason-no-local-baseline})))
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
              _ (when (and reconcile-passphrase
                           (seq (:conflict-keys reconcile-analysis)))
                  (throw (ex-info "local and remote have overlapping key changes; manual reconciliation required"
                                  {:reason reasons/reason-overlapping-changes})))]
          (if (:dry-run? opts)
            (let [payload {:ok true
                           :action "sync_pull"
                           :exit_code 0
                           :remote remote-name
                           :remote_type "fs"
                           :remote_path (get remote "path")
                           :bundle_path bundle-path
                           :vault_path vault-path
                           :remote_rev remote-rev
                           :dry_run true
                           :reconcile reconcile?
                           :remote_changed remote-changed
                           :local_changed local-changed
                           :has_conflict has-conflict
                           :has_remote true
                           :has_local local-exists?}]
              (if json?
                (success-json payload)
                (result {:exit-code 0
                         :stdout (format "dry-run: would pull %s -> %s\n" bundle-path vault-path)})))
            (let [backup-path (when local-exists?
                                (let [p (str vault-path ".bak." (System/currentTimeMillis))]
                                  (copy-file! vault-path p)
                                  p))
                  _ (if reconcile-passphrase
                      (apply-sync-reconcile-merge! vault-path reconcile-passphrase reconcile-local-snap reconcile-remote-snap reconcile-analysis)
                      (bundle/open-to-vault-file bundle-path vault-path identity true))
                  local-hash (file-sha256-hex vault-path)
                  sync-passphrase (or reconcile-passphrase
                                      (maybe-sync-passphrase ctx opts))
                  baseline-secret-hashes (if reconcile-passphrase
                                           (:hashes reconcile-remote-snap)
                                           (when sync-passphrase
                                             (:hashes (load-vault-snapshot vault-path sync-passphrase false))))
                  _ (config/config-sync-mark-seen! (:config-path ctx) remote-name remote-rev local-hash baseline-secret-hashes)
                  payload {:ok true
                           :action "sync_pull"
                           :exit_code 0
                           :remote remote-name
                           :remote_type "fs"
                           :remote_path (get remote "path")
                           :bundle_path bundle-path
                           :vault_path vault-path
                           :remote_rev remote-rev
                           :last_seen_rev remote-rev
                           :local_hash local-hash
                           :has_baseline_hashes (boolean baseline-secret-hashes)
                           :reconciled (boolean reconcile-passphrase)
                           :remote_changed remote-changed
                           :local_changed local-changed
                           :has_conflict has-conflict
                           :has_remote true
                           :has_local true
                           :backup_path backup-path}]
              (if json?
                (success-json payload)
                (result {:exit-code 0
                         :stdout (format "ok (pulled %s)\n" remote-name)})))))
        (catch Exception e
          (sync-error-result json? e))))))

(defn- sync-transfer-args
  [{:keys [remote json? dry-run? force? reconcile? passphrase-cmd passphrase-stdin?]}]
  (cond-> []
    (some? remote) (conj "--remote" remote)
    dry-run? (conj "--dry-run")
    force? (conj "--force")
    reconcile? (conj "--reconcile")
    passphrase-stdin? (conj "--passphrase-stdin")
    (some? passphrase-cmd) (conj "--passphrase-cmd" passphrase-cmd)
    json? (conj "--json")))

(defn- handle-sync-auto
  [ctx args]
  (let [[opts parse-error] (parse-sync-transfer-opts args)
        json? (:json? opts)]
    (if parse-error
      (sync-error-result json? (ex-info parse-error {:reason reasons/reason-sync-failed}))
      (try
        (let [remote (select-sync-remote! ctx (:remote opts))
              status (sync-status-payload ctx remote)
              remote-name (:remote status)
              push-args (sync-transfer-args {:remote remote-name
                                             :json? json?
                                             :dry-run? (:dry-run? opts)
                                             :force? (:force? opts)
                                             :passphrase-cmd (:passphrase-cmd opts)
                                             :passphrase-stdin? (:passphrase-stdin? opts)})
              pull-args (sync-transfer-args {:remote remote-name
                                             :json? json?
                                             :dry-run? (:dry-run? opts)
                                             :reconcile? (:reconcile? opts)
                                             :passphrase-cmd (:passphrase-cmd opts)
                                             :passphrase-stdin? (:passphrase-stdin? opts)})
              decision (cond
                         (:has_lock status) :blocked-lock
                         (:has_conflict status) (if (:reconcile? opts) :pull :blocked-overlap)
                         (:remote_changed status) :pull
                         (:needs_pull status) :pull
                         (:local_changed status) :push
                         (and (:has_local status) (:can_push status) (not (:has_remote status))) :push
                         :else :noop)]
          (case decision
            :push (handle-sync-push ctx push-args)
            :pull (handle-sync-pull ctx pull-args)
            :blocked-lock (sync-error-result json? (ex-info "remote lock present"
                                                            {:reason reasons/reason-remote-lock-present}))
            :blocked-overlap (sync-error-result json? (ex-info "local and remote have overlapping changes; rerun with --reconcile"
                                                               {:reason reasons/reason-overlapping-changes}))
            :noop (if json?
                    (success-json {:ok true
                                   :action "sync_auto"
                                   :exit_code 0
                                   :remote remote-name
                                   :decision "noop"
                                   :recommended_action (:recommended_action status)
                                   :in_sync (:in_sync status)})
                    (result {:exit-code 0
                             :stdout (format "ok (sync noop %s)\n" remote-name)}))))
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
                          {:reason reasons/reason-sync-failed})))
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
                          {:reason reasons/reason-sync-failed})))
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
                             :reason "lock_missing"})
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
                                      {:reason reasons/reason-sync-failed})))
                  _ (when-not (:yes? opts)
                      (throw (ex-info "refusing to remove lock without --yes"
                                      {:reason reasons/reason-sync-failed})))
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
                                  (not removed?) (assoc :reason "lock_missing")
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
        (let [remote (-> (select-sync-remote! ctx (:remote opts))
                         ensure-fs-remote!)
              remote-name (get remote "name")
              clear? (:clear? opts)
              to-remote? (:to-remote? opts)
              rev (some-> (:rev opts) str/trim not-empty)
              selected-modes (count (filter identity [clear? to-remote? (some? rev)]))
              existing-entry (config/config-sync-entry (:config-path ctx) remote-name)
              previous-rev (some-> existing-entry (get "last_seen_rev") str/trim not-empty)]
          (when-not (:yes? opts)
            (throw (ex-info "sync reset-baseline requires --yes"
                            {:reason reasons/reason-sync-failed})))
          (when (not= selected-modes 1)
            (throw (ex-info "choose exactly one mode: --to-remote, --clear, or --rev <sha256>"
                            {:reason reasons/reason-sync-failed})))
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
                            (let [bundle-path (fs-remote-bundle-path remote)
                                  _ (when (str/blank? bundle-path)
                                      (throw (ex-info "remote bundle path is empty" {:reason reasons/reason-missing-path})))
                                  _ (when-not (.exists (io/file bundle-path))
                                      (throw (ex-info (format "remote bundle missing: %s" bundle-path)
                                                      {:reason reasons/reason-remote-bundle-missing})))]
                              (file-sha256-hex bundle-path)))
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
                                  {:reason reasons/reason-sync-failed})))
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
      :else (error-text 1 "unknown sync command"))))

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

(defn- handle-vault
  [ctx args]
  (let [args (vec args)]
    (cond
      (= "init" (first args)) (handle-vault-init ctx (rest args))
      (= "info" (first args)) (handle-vault-info ctx (rest args))
      (= "path" (first args)) (handle-vault-path ctx (rest args))
      :else (error-text 1 "unknown vault command"))))

(defn- secret-vault-path
  [ctx opts]
  (vault-path/resolve-vault-path ctx (:vault-path opts)))

(defn- secret-json-success
  [payload]
  (success-json (assoc payload :ok true :exit_code 0)))

(defn- handle-secret-set
  [ctx args]
  (let [[opts parse-error] (parse-secret-set-opts args)
        json? (:json? opts)]
    (cond
      parse-error
      (secret-error-result json? (ex-info parse-error {:reason reasons/reason-secret-failed}))

      (str/blank? (:name opts))
      (secret-error-result json? (ex-info "empty secret name" {:reason reasons/reason-empty-secret-name}))

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
          (secret-error-result json? e))))))

(defn- handle-secret-list
  [ctx args]
  (let [[opts parse-error] (parse-secret-common-opts args)
        json? (:json? opts)]
    (cond
      parse-error
      (secret-error-result json? (ex-info parse-error {:reason reasons/reason-secret-failed}))

      (seq (:rest opts))
      (secret-error-result json? (ex-info "unexpected argument" {:reason reasons/reason-secret-failed}))

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
          (secret-error-result json? e))))))

(defn- handle-secret-get
  [ctx args]
  (let [[opts parse-error] (parse-secret-common-opts args)
        json? (:json? opts)
        names (:rest opts)]
    (cond
      parse-error
      (secret-error-result json? (ex-info parse-error {:reason reasons/reason-secret-failed}))

      (not= 1 (count names))
      (secret-error-result json? (ex-info "expected secret name" {:reason reasons/reason-empty-secret-name}))

      (not (:unsafe-stdout? opts))
      (secret-error-result json? (ex-info "refusing to print secrets; use --unsafe-stdout if you really want this"
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
          (secret-error-result json? e))))))

(defn- handle-secret-rm
  [ctx args]
  (let [[opts parse-error] (parse-secret-common-opts args)
        json? (:json? opts)
        names (:rest opts)]
    (cond
      parse-error
      (secret-error-result json? (ex-info parse-error {:reason reasons/reason-secret-failed}))

      (not= 1 (count names))
      (secret-error-result json? (ex-info "expected secret name" {:reason reasons/reason-empty-secret-name}))

      :else
      (try
        (let [path (secret-vault-path ctx opts)
              pp (resolve-passphrase! ctx opts)
              {:keys [name]} (vault-v2/delete-secret! path pp (first names))]
          (if json?
            (secret-json-success {:action "rm" :name name})
            (result {:exit-code 0 :stdout "ok\n"})))
        (catch Exception e
          (secret-error-result json? e))))))

(defn- handle-secret-mv
  [ctx args]
  (let [[opts parse-error] (parse-secret-common-opts args)
        json? (:json? opts)
        names (:rest opts)]
    (cond
      parse-error
      (secret-error-result json? (ex-info parse-error {:reason reasons/reason-secret-failed}))

      (not= 2 (count names))
      (secret-error-result json? (ex-info "expected old and new secret names" {:reason reasons/reason-empty-secret-name}))

      :else
      (try
        (let [path (secret-vault-path ctx opts)
              pp (resolve-passphrase! ctx opts)
              {:keys [from to]} (vault-v2/rename-secret! path pp (first names) (second names))]
          (if json?
            (secret-json-success {:action "mv" :from from :to to})
            (result {:exit-code 0 :stdout "ok\n"})))
        (catch Exception e
          (secret-error-result json? e))))))

(defn- handle-secret
  [ctx args]
  (let [args (vec args)]
    (cond
      (= "set" (first args)) (handle-secret-set ctx (rest args))
      (= "list" (first args)) (handle-secret-list ctx (rest args))
      (= "get" (first args)) (handle-secret-get ctx (rest args))
      (or (= "rm" (first args)) (= "delete" (first args))) (handle-secret-rm ctx (rest args))
      (= "mv" (first args)) (handle-secret-mv ctx (rest args))
      :else (error-text 1 "unknown secret command"))))

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
                      {:reason reasons/reason-invalid-mapping})))
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
    (:value (vault-v2/get-secret vault-path passphrase secret-name))))

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

      (str/blank? (:out-dir opts))
      (projection-error-result json? (ex-info "missing render target" {:reason reasons/reason-missing-render-target}))

      :else
      (try
        (let [{:keys [request env-paths]} (resolve-mappings! ctx opts reasons/reason-projection-failed)
              _ (when (some-> (:stdin request) str/trim not-empty)
                  (throw (ex-info "stdin projection is only supported for `kimen run`"
                                  {:reason reasons/reason-stdin-not-supported})))
              _ (validate-envpaths! request env-paths (:out-dir opts) false)
              _ (when (empty? (:files request))
                  (throw (ex-info "no files to render" {:reason reasons/reason-no-files-to-render})))
              vault-path (vault-path/resolve-vault-path ctx (:vault-path opts))
              pp (resolve-passphrase! ctx opts)
              lookup (make-secret-lookup vault-path pp)
              n (projection/render-files! {:lookup-secret lookup} (:out-dir opts) (:files request))]
          (if json?
            (success-json {:ok true
                           :action "render"
                           :exit_code 0
                           :out_dir (:out-dir opts)
                           :file_count n})
            (result {:exit-code 0
                     :stdout "ok\n"})))
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
      (= "keygen" (first args)) (handle-bundle-keygen (rest args))
      (= "recipient" (first args)) (handle-bundle-recipient ctx (rest args))
      (= "seal" (first args)) (handle-bundle-seal ctx (rest args))
      (= "open" (first args)) (handle-bundle-open ctx (rest args))
      :else (error-text 1 "unknown bundle command"))))

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
      (= "ci-pr-safety" (first args)) (handle-init-ci-pr-safety (rest args))
      (= "ci-deploy" (first args)) (handle-init-ci-deploy (rest args))
      (= "ci-sync-gate" (first args)) (handle-init-ci-sync-gate (rest args))
      :else (error-text 1 "unknown init command"))))

(defn- handle-project
  [ctx args]
  (let [args (vec args)]
    (cond
      (empty? args)
      (result {:exit-code 0
               :stdout project-usage})

      (some #{"help" "--help" "-h"} [(first args)])
      (result {:exit-code 0
               :stdout project-usage})

      (= "run" (first args))
      (handle-run ctx (rest args))

      (= "render" (first args))
      (handle-render ctx (rest args))

      (= "plan" (first args))
      (handle-plan ctx (rest args))

      :else
      (error-text 1 "unknown project command"))))

(defn run
  [ctx argv]
  (let [raw-args (vec argv)
        args (if (and (seq raw-args) (= "--" (first raw-args)))
               (subvec raw-args 1)
               raw-args)]
    (cond
      (empty? args)
      (result {:exit-code 0
               :stdout usage})

      (some #{(first args)} ["help" "--help" "-h"])
      (result {:exit-code 0
               :stdout usage})

      (= "version" (first args))
      (handle-version (rest args))

      (= "config" (first args))
      (handle-config ctx (rest args))

      (= "vault" (first args))
      (handle-vault ctx (rest args))

      (= "secret" (first args))
      (handle-secret ctx (rest args))

      (= "run" (first args))
      (handle-run ctx (rest args))

      (= "render" (first args))
      (handle-render ctx (rest args))

      (= "envfile" (first args))
      (handle-envfile ctx (rest args))

      (= "bundle" (first args))
      (handle-bundle ctx (rest args))

      (= "doctor" (first args))
      (handle-doctor ctx (rest args))

      (= "init" (first args))
      (handle-init (rest args))

      (= "remote" (first args))
      (handle-remote ctx (rest args))

      (= "sync" (first args))
      (handle-sync ctx (rest args))

      (and (= "map" (first args)) (= "lint" (second args)))
      (handle-map-lint ctx (drop 2 args))

      (= "plan" (first args))
      (handle-plan ctx (rest args))

      (= "project" (first args))
      (handle-project ctx (rest args))

      :else
      (result {:exit-code 1
               :stderr (str "unknown command\n\n" usage)}))))

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
     "  kimen sync status [--remote <name>] [--json]"
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
               :remote nil}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

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

(defn- sync-status-payload
  [ctx remote]
  (let [remote-name (get remote "name")
        remote-type (or (some-> (get remote "type") str/trim not-empty) "fs")
        remote-path (some-> (get remote "path") str/trim)
        bundle-path (when (= remote-type "fs") (fs-remote-bundle-path remote))
        lock-path (when bundle-path (str bundle-path ".lock"))
        has-remote (boolean (and bundle-path (.exists (io/file bundle-path))))
        has-lock (boolean (and lock-path (.exists (io/file lock-path))))
        vault-path (vault-path/resolve-vault-path ctx nil)
        has-local (boolean (.exists (io/file vault-path)))
        recipient (some-> (get remote "recipient") str/trim not-empty)
        identity (some-> (get remote "identity") str/trim not-empty)
        blockers (cond-> []
                   (and has-local (nil? recipient)) (conj "remote_recipient_missing")
                   (and has-remote (not has-local) (nil? identity)) (conj "remote_identity_missing")
                   has-lock (conj "remote_lock_present"))
        can-push (boolean (and has-local (not has-lock) (some? recipient)))
        needs-pull (boolean (and has-remote (not has-local)))
        in-sync false
        recommended-action
        (cond
          has-lock "wait_or_sync_unlock"
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
     :has_remote has-remote
     :has_lock has-lock
     :has_local has-local
     :in_sync in-sync
     :can_push can-push
     :needs_pull needs-pull
     :lock_blocks_push has-lock
     :likely_stale false
     :lock_age_seconds 0
     :blockers blockers
     :recommended_action recommended-action}))

(defn- handle-sync-status
  [ctx args]
  (let [[opts parse-error] (parse-sync-status-opts args)
        json? (:json? opts)]
    (if parse-error
      (sync-error-result json? (ex-info parse-error {:reason reasons/reason-sync-failed}))
      (try
        (let [remote (select-sync-remote! ctx (:remote opts))
              payload (sync-status-payload ctx remote)]
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
      (= "init" (first args)) (handle-sync-init ctx (rest args))
      (= "status" (first args)) (handle-sync-status ctx (rest args))
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

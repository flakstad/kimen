(ns kimen.cli
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
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
     "  kimen run [--map <path>|--profile <name>] [--env VAR=<value>] [--file relpath=<value>] [--envpath VAR=relpath] [--stdin <value>] [--files-dir <path>] [--json] [--dry-run] [-- <command> [args...]]"
     "  kimen render [--map <path>|--profile <name>] [--file relpath=<value>] --dir <path> [--json]"
     "  kimen envfile [--map <path>|--profile <name>] [--env VAR=<value>] [--file relpath=<value>] [--envpath VAR=relpath] --out <path> [--files-dir <path>] [--json]"
     "  kimen map lint [--map <path>|--profile <name>] [--mode all|run|render|envfile] [--strict] [--json]"
     "  kimen plan [--map <path>|--profile <name>] [--env VAR=<value>] [--file relpath=<value>] [--envpath VAR=relpath] [--stdin <value>] [--against-map <path>|--against-profile <name>] [--mode run|render|envfile] [--json] [-- <command> [args...]]"
     "  kimen project run [--map <path>|--profile <name>] [--env VAR=<value>] [--file relpath=<value>] [--envpath VAR=relpath] [--stdin <value>] [--files-dir <path>] [--json] [--dry-run] [-- <command> [args...]]"
     "  kimen project render [--map <path>|--profile <name>] [--file relpath=<value>] --dir <path> [--json]"
     "  kimen project plan [--map <path>|--profile <name>] [--env VAR=<value>] [--file relpath=<value>] [--envpath VAR=relpath] [--stdin <value>] [--against-map <path>|--against-profile <name>] [--mode run|render|envfile] [--json] [-- <command> [args...]]"
     ""]))

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

      (and (= "map" (first args)) (= "lint" (second args)))
      (handle-map-lint ctx (drop 2 args))

      (= "plan" (first args))
      (handle-plan ctx (rest args))

      (and (= "project" (first args)) (= "run" (second args)))
      (handle-run ctx (drop 2 args))

      (and (= "project" (first args)) (= "render" (second args)))
      (handle-render ctx (drop 2 args))

      (and (= "project" (first args)) (= "plan" (second args)))
      (handle-plan ctx (drop 2 args))

      :else
      (result {:exit-code 1
               :stderr (str "unknown command\n\n" usage)}))))

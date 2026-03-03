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
    [kimen.passphrase :as passphrase]
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
     "  kimen map lint --map <path> [--mode all|run|render|envfile] [--json]"
     "  kimen plan --map <path> [--mode run|render|envfile] [--json] [-- <command> [args...]]"
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

(defn- parse-map-lint-opts
  [args]
  (loop [args args
         opts {:json? false
               :mode "all"
               :map-path nil}]
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

          (str/blank? (:map-path opts))
          (map-lint/invalid-input-report mode "missing --map")

          :else
          (try
            (let [source ((:read-file ctx) (:map-path opts))]
              (map-lint/lint-source {:source source :mode mode}))
            (catch Exception e
              (map-lint/invalid-input-report mode (.getMessage e)))))
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

      (str/blank? (:map-path opts))
      (plan-error json? reasons/reason-plan-failed "missing --map")

      :else
      (try
        (let [source ((:read-file ctx) (:map-path opts))
              payload (plan/plan-from-source {:source source
                                              :mode (:mode opts)
                                              :command (:command opts)})]
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

      (and (= "map" (first args)) (= "lint" (second args)))
      (handle-map-lint ctx (drop 2 args))

      (= "plan" (first args))
      (handle-plan ctx (rest args))

      :else
      (result {:exit-code 1
               :stderr (str "unknown command\n\n" usage)}))))

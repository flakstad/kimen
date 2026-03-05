(ns kimen.cli.parse
  (:require
   [clojure.string :as str]))

(def ^:private duration-unit-ms
  {"ms" 1
   "s" 1000
   "m" (* 60 1000)
   "h" (* 60 60 1000)
   "d" (* 24 60 60 1000)})

(defn parse-flag-value
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

(defn parse-duration-ms
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

(defn parse-json-only-opts
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

(defn split-before-double-dash
  [args]
  (loop [left []
         args args]
    (if (empty? args)
      [left []]
      (if (= "--" (first args))
        [left (rest args)]
        (recur (conj left (first args)) (rest args))))))

(defn parse-map-lint-opts
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

(defn parse-plan-opts
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

(defn parse-vault-auth-opts
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

(defn parse-vault-rekey-opts
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

(defn parse-secret-set-opts
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

(defn parse-secret-common-opts
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

(defn parse-run-opts
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

(defn parse-render-opts
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

(defn parse-envfile-opts
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

(defn parse-doctor-opts
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

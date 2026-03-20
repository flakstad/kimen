(ns kimen.cli.parse
  (:require
   [clojure.string :as str]
   [kimen.commands.init :as init]
   [kimen.json :as json]))

(set! *warn-on-reflection* true)

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

(defn parse-bundle-keygen-opts
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

(defn parse-bundle-recipient-opts
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

(defn parse-bundle-seal-opts
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

(defn parse-bundle-open-opts
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

(defn parse-remote-name-opts
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

(defn parse-remote-upsert-opts
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

(defn parse-sync-init-opts
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

(defn parse-sync-status-opts
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

(defn parse-sync-transfer-opts
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

(defn parse-sync-auto-opts
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

(defn parse-sync-reset-baseline-opts
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

(defn parse-sync-restore-opts
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

(defn parse-sync-changes-opts
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

(defn parse-sync-resolve-opts
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

(defn parse-sync-preflight-opts
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

(defn parse-sync-unlock-opts
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

(defn parse-init-ci-pr-safety-opts
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

(defn parse-init-ci-deploy-opts
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

(defn parse-init-ci-sync-gate-opts
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

(defn parse-config-show-opts
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

(defn parse-config-unlock-set-opts
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

(defn parse-config-vault-set-opts
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

(defn parse-json-map
  [s]
  (let [s (some-> s str/trim)]
    (when-not (str/blank? s)
      (try
        (let [v (json/read-str s)]
          (when (map? v)
            v))
        (catch Exception _ nil)))))

(defn parse-cmd-string
  [s]
  (->> (str/split (str/trim (str s)) #"\s+")
       (remove str/blank?)
       vec))

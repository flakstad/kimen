(ns kimen.migration.go-vault
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as sh]
   [clojure.string :as str]
   [kimen.json :as json])
  (:import
   [java.util Base64]))

(def ^:private default-target-bin "bin/kimen")

(defn- usage
  []
  (str/join
   "\n"
   ["Usage: bb migrate-go-vault -- --source-bin <path> --source-vault <path> --source-passphrase-cmd <cmd> --target-vault <path> --target-passphrase-cmd <cmd> [--target-bin <path>] [--init-target] [--dry-run] [--json]"
    ""
    "Options:"
    "  --source-bin <path>            Path to Go-era kimen binary"
    "  --source-vault <path>          Path to Go-era vault file"
    "  --source-passphrase-cmd <cmd>  Command that prints source vault passphrase"
    "  --target-bin <path>            Path to Clojure kimen binary (default: bin/kimen)"
    "  --target-vault <path>          Path to Clojure vault file"
    "  --target-passphrase-cmd <cmd>  Command that prints target vault passphrase"
    "  --init-target                  Initialize target vault if missing"
    "  --dry-run                      Read source vault without writing target"
    "  --json                         Print JSON result"
    "  --help                         Show this help"]))

(defn- parse-flag-value
  [args flag]
  (let [a (first args)]
    (cond
      (= a flag)
      (if-let [v (second args)]
        [v (nnext args) nil]
        [nil nil (str flag " requires a value")])

      (str/starts-with? a (str flag "="))
      [(subs a (inc (count flag))) (rest args) nil]

      :else
      [nil args nil])))

(defn parse-args
  [argv]
  (loop [args (seq (drop-while #(= "--" %) argv))
         opts {:target-bin default-target-bin
               :init-target? false
               :dry-run? false
               :json? false
               :help? false}]
    (if (empty? args)
      (let [missing (->> [[:source-bin "--source-bin is required"]
                          [:source-vault "--source-vault is required"]
                          [:source-passphrase-cmd "--source-passphrase-cmd is required"]
                          [:target-vault "--target-vault is required"]
                          [:target-passphrase-cmd "--target-passphrase-cmd is required"]]
                         (keep (fn [[k msg]]
                                 (when (and (not (:help? opts)) (str/blank? (str (get opts k))))
                                   msg))))]
        (if (seq missing)
          {:error (str/join "; " missing)}
          {:opts opts}))
      (let [a (first args)]
        (cond
          (or (= a "--help") (= a "-h"))
          (recur (rest args) (assoc opts :help? true))

          (= a "--init-target")
          (recur (rest args) (assoc opts :init-target? true))

          (= a "--dry-run")
          (recur (rest args) (assoc opts :dry-run? true))

          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          :else
          (let [[source-bin args err] (parse-flag-value args "--source-bin")]
            (if err
              {:error err}
              (if source-bin
                (recur args (assoc opts :source-bin source-bin))
                (let [[source-vault args err] (parse-flag-value args "--source-vault")]
                  (if err
                    {:error err}
                    (if source-vault
                      (recur args (assoc opts :source-vault source-vault))
                      (let [[source-passphrase-cmd args err] (parse-flag-value args "--source-passphrase-cmd")]
                        (if err
                          {:error err}
                          (if source-passphrase-cmd
                            (recur args (assoc opts :source-passphrase-cmd source-passphrase-cmd))
                            (let [[target-bin args err] (parse-flag-value args "--target-bin")]
                              (if err
                                {:error err}
                                (if target-bin
                                  (recur args (assoc opts :target-bin target-bin))
                                  (let [[target-vault args err] (parse-flag-value args "--target-vault")]
                                    (if err
                                      {:error err}
                                      (if target-vault
                                        (recur args (assoc opts :target-vault target-vault))
                                        (let [[target-passphrase-cmd args err] (parse-flag-value args "--target-passphrase-cmd")]
                                          (if err
                                            {:error err}
                                            (if target-passphrase-cmd
                                              (recur args (assoc opts :target-passphrase-cmd target-passphrase-cmd))
                                              {:error (str "unknown flag " a)})))))))))))))))))))))))

(defn- default-exec
  [{:keys [bin args stdin]}]
  (let [res (apply sh/sh (concat [bin]
                                 args
                                 (cond-> [:out :string :err :string]
                                   (some? stdin) (conj :in stdin))))]
    {:exit (:exit res)
     :out (or (:out res) "")
     :err (or (:err res) "")}))

(defn- command-display
  [bin args]
  (str/join " " (map pr-str (into [bin] args))))

(defn- run-command!
  [exec-fn bin args]
  (let [{:keys [exit out err]} (exec-fn {:bin bin :args args})]
    (when-not (zero? exit)
      (throw (ex-info (format "command failed: %s" (command-display bin args))
                      {:exit exit
                       :stdout out
                       :stderr err
                       :command [bin args]})))
    out))

(defn- run-json-command!
  [exec-fn bin args]
  (let [out (run-command! exec-fn bin args)]
    (try
      (json/read-str out)
      (catch Exception e
        (throw (ex-info (format "invalid JSON output from command: %s" (command-display bin args))
                        {:stdout out
                         :command [bin args]}
                        e))))))

(defn- decode-utf8!
  [secret-name value-b64]
  (try
    (String. (.decode (Base64/getDecoder) (str value-b64)) "UTF-8")
    (catch Exception e
      (throw (ex-info (format "failed to decode UTF-8 value for secret %s" (pr-str secret-name))
                      {:secret secret-name
                       :value_b64 value-b64}
                      e)))))

(defn migrate!
  ([opts]
   (migrate! opts default-exec))
  ([{:keys [source-bin source-vault source-passphrase-cmd
            target-bin target-vault target-passphrase-cmd
            init-target? dry-run?] :as _opts}
    exec-fn]
   (let [target-exists? (.exists (io/file target-vault))
         initialized? (atom false)]
     (when (and init-target? (not target-exists?) (not dry-run?))
       (run-command! exec-fn
                     target-bin
                     ["vault" "init"
                      "--vault" target-vault
                      "--passphrase-cmd" target-passphrase-cmd
                      "--json"])
       (reset! initialized? true))
     (let [listed (run-json-command! exec-fn
                                     source-bin
                                     ["secret" "list"
                                      "--vault" source-vault
                                      "--passphrase-cmd" source-passphrase-cmd
                                      "--json"])
           names (->> (get listed "names") (filter string?) vec)]
       (doseq [name names]
         (let [got (run-json-command! exec-fn
                                      source-bin
                                      ["secret" "get"
                                       name
                                       "--unsafe-stdout"
                                       "--vault" source-vault
                                       "--passphrase-cmd" source-passphrase-cmd
                                       "--json"])
               value (decode-utf8! name (get got "value_b64"))]
           (when-not dry-run?
             (run-command! exec-fn
                           target-bin
                           ["secret" "set"
                            name
                            "--value" value
                            "--vault" target-vault
                            "--passphrase-cmd" target-passphrase-cmd
                            "--json"]))))
       {:ok true
        :action "migrate_go_vault"
        :exit_code 0
        :source source-vault
        :target target-vault
        :count (count names)
        :names names
        :dry_run dry-run?
        :target_initialized @initialized?}))))

(defn- human-summary
  [payload]
  (format "migrated %d secret(s) from %s to %s%s\n"
          (int (or (get payload :count) 0))
          (get payload :source)
          (get payload :target)
          (if (true? (get payload :dry_run)) " (dry-run)" "")))

(defn main!
  [argv]
  (let [{:keys [opts error]} (parse-args argv)
        json? (:json? opts)]
    (cond
      error
      (do
        (binding [*out* *err*]
          (println error)
          (println)
          (println (usage)))
        1)

      (:help? opts)
      (do
        (println (usage))
        0)

      :else
      (try
        (let [payload (migrate! opts)]
          (if json?
            (println (json/write-str payload))
            (print (human-summary payload)))
          0)
        (catch Exception e
          (if json?
            (binding [*out* *err*]
              (println (json/write-str {:ok false
                                        :action "migrate_go_vault"
                                        :exit_code 1
                                        :error (.getMessage e)})))
            (binding [*out* *err*]
              (println (.getMessage e))))
          1)))))

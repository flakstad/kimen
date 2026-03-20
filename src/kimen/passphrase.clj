(ns kimen.passphrase
  (:require
    [clojure.java.shell :as sh]
    [clojure.string :as str]
    [kimen.config :as config]
    [kimen.reason-codes :as reasons]))

(set! *warn-on-reflection* true)

(def env-passphrase "KIMEN_PASSPHRASE")

(defn- getenv
  [ctx k]
  (if-let [lookup (:getenv ctx)]
    (lookup k)
    (System/getenv k)))

(defn- fail!
  [reason message]
  (throw (ex-info message {:reason reason})))

(defn- read-line-one
  [r]
  (let [br (java.io.BufferedReader. (java.io.InputStreamReader. r))
        line (.readLine br)
        line (some-> line str/trim)]
    (if (str/blank? line)
      (fail! reasons/reason-missing-passphrase "no passphrase provided")
      line)))

(defn- passphrase-from-exec
  [argv]
  (when (empty? argv)
    (fail! reasons/reason-empty-passphrase-command "empty --passphrase-cmd"))
  (let [[cmd & args] argv
        {:keys [exit out err]} (apply sh/sh (concat [cmd] args [:out :string :err :string]))]
    (when-not (zero? exit)
      (fail! reasons/reason-passphrase-command-failed
             (format "passphrase command failed: %s" (or (some-> err str/trim not-empty) (some-> out str/trim not-empty) "non-zero exit"))))
    (let [line (some-> out str/split-lines first str/trim)]
      (if (str/blank? line)
        (fail! reasons/reason-missing-passphrase "no passphrase provided")
        line))))

(defn- prompt-passphrase
  [ctx]
  (if-let [prompt-reader (:prompt-reader ctx)]
    (let [line (some-> (prompt-reader "Passphrase: ") str/trim)]
      (if (str/blank? line)
        (fail! reasons/reason-missing-passphrase "no passphrase provided")
        line))
    (if-let [console (System/console)]
      (let [chars (.readPassword console "Passphrase: " (object-array 0))
            line (some-> chars String. str/trim)]
        (if (str/blank? line)
          (fail! reasons/reason-missing-passphrase "no passphrase provided")
          line))
      (fail! reasons/reason-missing-passphrase "no passphrase provided (set KIMEN_PASSPHRASE, use --passphrase-stdin/--passphrase-cmd, or configure `kimen config unlock ...`)"))))

(defn- parse-cmd-string
  [s]
  (->> (str/split (str/trim (str s)) #"\s+")
       (remove str/blank?)
       vec))

(defn resolve-passphrase-info
  [{:keys [config-path stdin prompt-reader]
    env-getter :getenv
    :or {stdin System/in}}
   {:keys [passphrase-cmd passphrase-stdin?]}]
  (let [ctx {:config-path config-path
             :stdin stdin
             :getenv env-getter
             :prompt-reader prompt-reader}
        env-pass (fn []
                   (some-> (getenv ctx env-passphrase)
                           str/trim
                           not-empty))]
    (if-let [p (env-pass)]
      {:passphrase p
       :source :env}
      (do
        (when (and (some? passphrase-cmd) (str/blank? passphrase-cmd))
          (fail! reasons/reason-empty-passphrase-command "empty --passphrase-cmd"))
        (when (and (not (str/blank? passphrase-cmd)) passphrase-stdin?)
          (fail! reasons/reason-config-failed "conflicting passphrase sources"))
        (cond
          (not (str/blank? passphrase-cmd))
          {:passphrase (passphrase-from-exec (parse-cmd-string passphrase-cmd))
           :source :cmd}

          passphrase-stdin?
          {:passphrase (read-line-one stdin)
           :source :stdin}

          :else
          (let [{:keys [method exec]} (config/config-unlock-show config-path)
                method (or (some-> method str/lower-case str/trim) "prompt")]
            (case method
              "prompt" {:passphrase (prompt-passphrase ctx)
                        :source :prompt}
              "env" (if-let [p (env-pass)]
                      {:passphrase p
                       :source :env}
                      (fail! reasons/reason-missing-passphrase (format "unlock.method=env but %s is not set" env-passphrase)))
              "stdin" {:passphrase (read-line-one stdin)
                       :source :stdin}
              "exec" (if (seq exec)
                       {:passphrase (passphrase-from-exec exec)
                        :source :exec}
                       (fail! reasons/reason-missing-unlock-exec-command "unlock.method=exec but unlock.exec is empty"))
              (fail! reasons/reason-unknown-unlock-method
                     (format "unknown unlock.method %s (expected prompt|env|stdin|exec)" (pr-str method))))))))))

(defn resolve-passphrase
  [ctx opts]
  (:passphrase (resolve-passphrase-info ctx opts)))

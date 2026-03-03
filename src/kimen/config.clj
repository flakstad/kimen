(ns kimen.config
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [kimen.json :as json])
  (:import
    [java.nio.file Files Path Paths StandardCopyOption]
    [java.nio.file.attribute PosixFilePermission]))

(def env-config-path "KIMEN_CONFIG")

(defn- config-root-dir
  []
  (let [os (some-> (System/getProperty "os.name") str/lower-case)
        home (some-> (System/getProperty "user.home") str/trim not-empty)]
    (cond
      (and os (str/includes? os "win"))
      (or (some-> (System/getenv "APPDATA") str/trim not-empty)
          (when home
            (str home "\\AppData\\Roaming")))

      (and os (str/includes? os "mac"))
      (when home
        (str home "/Library/Application Support"))

      :else
      (or (some-> (System/getenv "XDG_CONFIG_HOME") str/trim not-empty)
          (when home
            (str home "/.config"))))))

(defn default-kimen-dir
  []
  (let [root (config-root-dir)]
    (when-not root
      (throw (ex-info "config path unavailable"
                      {:reason "config_path_unavailable"})))
    (str root "/kimen")))

(defn default-config-path
  []
  (if-let [p (some-> (System/getenv env-config-path) str/trim not-empty)]
    p
    (str (default-kimen-dir) "/config.json")))

(defn resolve-config-path
  [override-path]
  (if-let [p (some-> override-path str/trim not-empty)]
    p
    (default-config-path)))

(defn- normalize-string
  [v]
  (when (string? v)
    (let [v (str/trim v)]
      (when-not (str/blank? v)
        v))))

(defn- normalize-string-vec
  [v]
  (when (sequential? v)
    (let [xs (->> v
                  (map normalize-string)
                  (remove nil?)
                  vec)]
      (when (seq xs)
        xs))))

(defn- normalize-config
  [m]
  (let [m (if (map? m) m {})
        vault-path (normalize-string (get-in m ["vault" "path"]))
        unlock-method (normalize-string (get-in m ["unlock" "method"]))
        unlock-exec (normalize-string-vec (get-in m ["unlock" "exec"]))
        remotes (if (vector? (get m "remotes"))
                  (vec (filter map? (get m "remotes")))
                  nil)
        sync (if (map? (get m "sync"))
               (get m "sync")
               nil)]
    (cond-> {}
      vault-path (assoc "vault" {"path" vault-path})
      (or unlock-method unlock-exec)
      (assoc "unlock" (cond-> {}
                         unlock-method (assoc "method" unlock-method)
                         unlock-exec (assoc "exec" unlock-exec)))
      (seq remotes) (assoc "remotes" remotes)
      (seq sync) (assoc "sync" sync))))

(defn load-config
  [config-path-override]
  (let [path (resolve-config-path config-path-override)
        f (io/file path)]
    (if-not (.exists f)
      [{} false]
      (try
        [(normalize-config (json/read-str (slurp f))) true]
        (catch Exception e
          (throw (ex-info (format "invalid config JSON at %s: %s" path (.getMessage e))
                          {:reason "invalid_config_json"
                           :config-path path}
                          e)))))))

(defn- ensure-parent-dir!
  [path]
  (let [f (io/file path)
        parent (.getParentFile f)]
    (when (and parent (not (.exists parent)))
      (when-not (.mkdirs parent)
        (throw (ex-info (format "failed to create config directory %s" (.getPath parent))
                        {:reason "config_failed"}))))
    (when parent
      (try
        (.setReadable parent false false)
        (.setReadable parent true true)
        (.setWritable parent false false)
        (.setWritable parent true true)
        (.setExecutable parent false false)
        (.setExecutable parent true true)
        (catch Exception _ nil)))))

(defn- set-posix-600!
  [^Path p]
  (try
    (Files/setPosixFilePermissions
      p
      #{PosixFilePermission/OWNER_READ PosixFilePermission/OWNER_WRITE})
    (catch Exception _ nil)))

(defn save-config!
  [config-path-override cfg]
  (let [path (resolve-config-path config-path-override)
        normalized (normalize-config cfg)
        content (str (json/write-str normalized) "\n")]
    (ensure-parent-dir! path)
    (let [target (Paths/get path (make-array String 0))
          parent (or (.getParent target) (Paths/get "." (make-array String 0)))
          tmp (Files/createTempFile parent "config." ".tmp" (make-array java.nio.file.attribute.FileAttribute 0))]
      (spit (.toFile tmp) content)
      (set-posix-600! tmp)
      (try
        (Files/move tmp target
                    (into-array StandardCopyOption
                                [StandardCopyOption/REPLACE_EXISTING
                                 StandardCopyOption/ATOMIC_MOVE]))
        (catch Exception _
          (Files/move tmp target
                      (into-array StandardCopyOption
                                  [StandardCopyOption/REPLACE_EXISTING]))))
      path)))

(def valid-unlock-methods #{"prompt" "env" "stdin" "exec"})

(defn config-show
  [config-path-override]
  (first (load-config config-path-override)))

(defn config-vault-show
  [config-path-override]
  (let [[cfg _] (load-config config-path-override)]
    (get-in cfg ["vault" "path"])))

(defn config-vault-set!
  [config-path-override vault-path]
  (let [vault-path (some-> vault-path str/trim)]
    (when (str/blank? vault-path)
      (throw (ex-info "missing vault path" {:reason "config_failed"})))
    (let [[cfg _] (load-config config-path-override)]
      (save-config! config-path-override (assoc-in cfg ["vault" "path"] vault-path)))))

(defn config-vault-clear!
  [config-path-override]
  (let [[cfg _] (load-config config-path-override)
        cfg' (if (contains? cfg "vault")
               (let [vault' (dissoc (get cfg "vault") "path")]
                 (if (seq vault')
                   (assoc cfg "vault" vault')
                   (dissoc cfg "vault")))
               cfg)]
    (save-config! config-path-override cfg')))

(defn config-unlock-show
  [config-path-override]
  (let [[cfg _] (load-config config-path-override)
        method (or (get-in cfg ["unlock" "method"]) "prompt")
        exec-cmd (get-in cfg ["unlock" "exec"])]
    {:method method
     :exec exec-cmd}))

(defn config-unlock-set!
  [config-path-override method exec-cmd]
  (let [method (some-> method str/lower-case str/trim)]
    (when-not (contains? valid-unlock-methods method)
      (throw (ex-info (format "unknown unlock method %s (expected prompt|env|stdin|exec)" (pr-str method))
                      {:reason "unknown_unlock_method"})))
    (when (and (= method "exec") (empty? exec-cmd))
      (throw (ex-info "unlock.method=exec requires command after --"
                      {:reason "missing_unlock_exec_command"})))
    (when (and (not= method "exec") (seq exec-cmd))
      (throw (ex-info "unlock command is only valid for method=exec"
                      {:reason "config_failed"})))
    (let [[cfg _] (load-config config-path-override)
          unlock (cond-> {"method" method}
                   (= method "exec") (assoc "exec" (vec exec-cmd)))]
      (save-config! config-path-override (assoc cfg "unlock" unlock)))))

(defn config-unlock-clear!
  [config-path-override]
  (let [[cfg _] (load-config config-path-override)]
    (save-config! config-path-override (dissoc cfg "unlock"))))

(ns kimen.config
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [kimen.bundle :as bundle]
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
(def remote-name-re #"^[A-Za-z0-9_.-]+$")
(def valid-remote-types #{"fs" "git"})

(defn normalize-remote-type
  [raw]
  (let [t (some-> raw str/lower-case str/trim)]
    (if (str/blank? t) "fs" t)))

(defn- trim-str
  [s]
  (some-> s str/trim))

(defn- apply-remote-defaults
  [remote]
  (let [name (trim-str (get remote "name"))
        type (normalize-remote-type (get remote "type"))
        path (trim-str (get remote "path"))
        recipient (trim-str (get remote "recipient"))
        identity (trim-str (get remote "identity"))
        branch (trim-str (get remote "branch"))
        bundle-path (trim-str (get remote "bundle_path"))]
    (cond-> {"name" name
             "type" type
             "path" path}
      (not (str/blank? recipient)) (assoc "recipient" recipient)
      (not (str/blank? identity)) (assoc "identity" identity)
      (= type "git") (assoc "branch" (if (str/blank? branch) "main" branch))
      (= type "git") (assoc "bundle_path" (if (str/blank? bundle-path) "vault.age" bundle-path)))))

(defn- validate-remote-name!
  [name]
  (let [name (trim-str name)]
    (when (str/blank? name)
      (throw (ex-info "empty remote name" {:reason "empty_remote_name"})))
    (when-not (re-matches remote-name-re name)
      (throw (ex-info (format "invalid remote name %s" (pr-str name))
                      {:reason "invalid_remote_name"})))
    name))

(defn- remote-endpoint-id
  [remote]
  (let [r (apply-remote-defaults remote)]
    [(get r "type")
     (get r "path")
     (when (= "git" (get r "type")) (get r "branch"))
     (when (= "git" (get r "type")) (get r "bundle_path"))]))

(defn- find-remote-index
  [remotes name]
  (first
    (keep-indexed (fn [idx remote]
                    (when (= (trim-str (get remote "name")) name)
                      idx))
                  remotes)))

(defn- validate-remote!
  [remote]
  (let [remote (apply-remote-defaults remote)
        name (validate-remote-name! (get remote "name"))
        type (normalize-remote-type (get remote "type"))
        path (trim-str (get remote "path"))
        branch (trim-str (get remote "branch"))
        bundle-path (trim-str (get remote "bundle_path"))]
    (when-not (contains? valid-remote-types type)
      (throw (ex-info (format "unsupported remote type %s (expected fs or git)" (pr-str type))
                      {:reason "unsupported_remote_type"})))
    (when (str/blank? path)
      (throw (ex-info "--path is required" {:reason "missing_path"})))
    (when (and (not= type "git")
               (or (not (str/blank? branch))
                   (not (str/blank? bundle-path))))
      (throw (ex-info "--branch/--bundle-path are only valid for --type git"
                      {:reason "git_fields_require_git_type"})))
    (assoc remote "name" name "type" type "path" path)))

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

(defn config-remote-list
  [config-path-override]
  (let [[cfg _] (load-config config-path-override)
        remotes (->> (or (get cfg "remotes") [])
                     (filter map?)
                     (map apply-remote-defaults)
                     (sort-by #(or (get % "name") ""))
                     vec)]
    remotes))

(defn config-remote-get
  [config-path-override name]
  (let [name (validate-remote-name! name)
        [cfg _] (load-config config-path-override)
        remotes (vec (or (get cfg "remotes") []))
        idx (find-remote-index remotes name)]
    (when (nil? idx)
      (throw (ex-info (format "remote %s not found" (pr-str name))
                      {:reason "remote_not_found"})))
    (apply-remote-defaults (nth remotes idx))))

(defn derive-recipient-from-identity-file
  [identity-path]
  (try
    (let [id (bundle/load-identity {:identity-file (trim-str identity-path)
                                    :from-stdin? false
                                    :stdin nil})]
      (bundle/recipient-for-identity id))
    (catch Exception e
      (throw (ex-info (format "derive recipient from identity: %s" (.getMessage e))
                      {:reason "recipient_derivation_failed"}
                      e)))))

(defn config-remote-add!
  [config-path-override remote]
  (let [name (validate-remote-name! (get remote "name"))
        remote (validate-remote! remote)
        [cfg _] (load-config config-path-override)
        remotes (vec (or (get cfg "remotes") []))]
    (when (some? (find-remote-index remotes name))
      (throw (ex-info (format "remote %s already exists" (pr-str name))
                      {:reason "remote_exists"})))
    (let [updated (->> (conj remotes remote)
                       (sort-by #(or (trim-str (get % "name")) ""))
                       vec)]
      (save-config! config-path-override (assoc cfg "remotes" updated))
      (apply-remote-defaults remote))))

(defn config-remote-set!
  [config-path-override name remote]
  (let [name (validate-remote-name! name)
        remote (validate-remote! remote)
        [cfg _] (load-config config-path-override)
        remotes (vec (or (get cfg "remotes") []))
        idx (find-remote-index remotes name)]
    (when (nil? idx)
      (throw (ex-info (format "remote %s not found" (pr-str name))
                      {:reason "remote_not_found"})))
    (let [old-remote (nth remotes idx)
          endpoint-changed? (not= (remote-endpoint-id old-remote)
                                  (remote-endpoint-id remote))
          sync-map (if (map? (get cfg "sync")) (get cfg "sync") nil)
          baseline-reset? (boolean (and endpoint-changed?
                                        sync-map
                                        (contains? sync-map name)))
          remotes' (assoc remotes idx remote)
          sync' (if baseline-reset?
                  (let [next-sync (dissoc sync-map name)]
                    (when (seq next-sync)
                      next-sync))
                  sync-map)
          cfg' (cond-> (assoc cfg "remotes" remotes')
                 sync' (assoc "sync" sync')
                 (nil? sync') (dissoc "sync"))]
      (save-config! config-path-override cfg')
      {:remote (apply-remote-defaults remote)
       :baseline-reset? baseline-reset?})))

(defn config-remote-remove!
  [config-path-override name]
  (let [name (validate-remote-name! name)
        [cfg _] (load-config config-path-override)
        remotes (vec (or (get cfg "remotes") []))
        idx (find-remote-index remotes name)]
    (when (nil? idx)
      (throw (ex-info (format "remote %s not found" (pr-str name))
                      {:reason "remote_not_found"})))
    (let [remotes' (vec (concat (subvec remotes 0 idx) (subvec remotes (inc idx))))
          sync-map (if (map? (get cfg "sync")) (get cfg "sync") nil)
          sync' (if sync-map
                  (let [next-sync (dissoc sync-map name)]
                    (when (seq next-sync)
                      next-sync))
                  nil)
          cfg' (cond-> (assoc cfg "remotes" remotes')
                 sync' (assoc "sync" sync')
                 (nil? sync') (dissoc "sync"))]
      (save-config! config-path-override cfg')
      name)))

(defn config-sync-entry
  [config-path-override remote-name]
  (let [[cfg _] (load-config config-path-override)
        sync-map (if (map? (get cfg "sync")) (get cfg "sync") {})]
    (get sync-map remote-name)))

(defn config-sync-mark-seen!
  ([config-path-override remote-name remote-rev]
   (config-sync-mark-seen! config-path-override remote-name remote-rev nil nil))
  ([config-path-override remote-name remote-rev local-hash]
   (config-sync-mark-seen! config-path-override remote-name remote-rev local-hash nil))
  ([config-path-override remote-name remote-rev local-hash baseline-secret-hashes]
   (let [[cfg _] (load-config config-path-override)
         sync-map (if (map? (get cfg "sync")) (get cfg "sync") {})
         entry (if (map? (get sync-map remote-name)) (get sync-map remote-name) {})
         entry' (cond-> (assoc entry
                               "last_seen_rev" remote-rev
                               "updated_at" (str (java.time.Instant/now)))
                  (some? local-hash) (assoc "local_hash" local-hash)
                  (some? baseline-secret-hashes) (assoc "baseline_secret_hashes" baseline-secret-hashes))
         cfg' (assoc cfg "sync" (assoc sync-map remote-name entry'))]
     (save-config! config-path-override cfg')
     entry')))

(defn config-sync-clear!
  [config-path-override remote-name]
  (let [[cfg _] (load-config config-path-override)
        sync-map (if (map? (get cfg "sync")) (get cfg "sync") {})
        next-sync (dissoc sync-map remote-name)
        cfg' (if (seq next-sync)
               (assoc cfg "sync" next-sync)
               (dissoc cfg "sync"))]
    (save-config! config-path-override cfg')
    remote-name))

(defn config-sync-replace!
  [config-path-override remote-name entry]
  (let [[cfg _] (load-config config-path-override)
        sync-map (if (map? (get cfg "sync")) (get cfg "sync") {})
        entry' (when (map? entry)
                 (assoc entry "updated_at" (str (java.time.Instant/now))))
        next-sync (if (some? entry')
                    (assoc sync-map remote-name entry')
                    (dissoc sync-map remote-name))
        cfg' (if (seq next-sync)
               (assoc cfg "sync" next-sync)
               (dissoc cfg "sync"))]
    (save-config! config-path-override cfg')
    entry'))

(ns kimen.commands.doctor
  (:require
    [clojure.java.io :as io]
    [clojure.java.shell :as sh]
    [clojure.string :as str]
    [kimen.bundle :as bundle]
    [kimen.commands.map-lint :as map-lint]
    [kimen.config :as config]
    [kimen.mapfile :as mapfile]
    [kimen.sync-state :as sync-state]
    [kimen.vault-path :as vault-path]
    [kimen.vault.v2 :as vault-v2])
  (:import
    [java.nio.file Files]
    [java.security MessageDigest]))

(set! *warn-on-reflection* true)

(def doctor-status-ok "ok")
(def doctor-status-warning "warning")
(def doctor-status-error "error")

(defn- add-check
  [state name status message]
  (-> state
      (update :checks conj {:name name
                            :status status
                            :message message})
      (update :error_count + (if (= status doctor-status-error) 1 0))
      (update :warning_count + (if (= status doctor-status-warning) 1 0))))

(defn- passphrase-source-check
  [state cfg cfg-valid?]
  (cond
    (some-> (System/getenv "KIMEN_PASSPHRASE") str/trim not-empty)
    (add-check state "passphrase_source" doctor-status-ok "using KIMEN_PASSPHRASE")

    (not cfg-valid?)
    (add-check state "passphrase_source" doctor-status-error "cannot determine passphrase source because config is invalid")

    :else
    (let [method (some-> (get-in cfg ["unlock" "method"]) str/lower-case str/trim)
          method (if (str/blank? method) "prompt" method)
          exec-cmd (get-in cfg ["unlock" "exec"])]
      (case method
        "prompt"
        (add-check state "passphrase_source" doctor-status-warning "no non-interactive passphrase source configured (TTY prompt fallback)")

        "env"
        (if (some-> (System/getenv "KIMEN_PASSPHRASE") str/trim not-empty)
          (add-check state "passphrase_source" doctor-status-ok "unlock.method=env with KIMEN_PASSPHRASE set")
          (add-check state "passphrase_source" doctor-status-error "unlock.method=env but KIMEN_PASSPHRASE is not set"))

        "stdin"
        (add-check state "passphrase_source" doctor-status-warning "unlock.method=stdin cannot be pre-validated in doctor")

        "exec"
        (if (seq exec-cmd)
          (add-check state "passphrase_source" doctor-status-ok (format "unlock.method=exec (%s)" (str/join " " exec-cmd)))
          (add-check state "passphrase_source" doctor-status-error "unlock.method=exec but unlock.exec is empty"))

        (add-check state "passphrase_source" doctor-status-error (format "unknown unlock.method %s" (pr-str method)))))))

(defn- mapping-checks
  [state {:keys [map-path profile]}]
  (cond
    (and (str/blank? map-path) (str/blank? profile))
    (add-check state "mapping_spec" doctor-status-ok "no --map/--profile provided; skipping mapping checks")

    :else
    (let [resolved-path
          (if (str/blank? profile)
            map-path
            (try
              (let [p (mapfile/resolve-profile profile)]
                (add-check state "mapping_profile" doctor-status-ok (format "%s -> %s" profile p))
                p)
              (catch Exception e
                (reduced (add-check state "mapping_spec" doctor-status-error (ex-message e))))))]
      (if (reduced? resolved-path)
        @resolved-path
        (try
          (let [source (slurp (io/file resolved-path))
                report (map-lint/lint-source {:source source :mode "all"})
                state (add-check state "mapping_parse" doctor-status-ok resolved-path)]
            (cond
              (pos? (:error_count report))
              (add-check state "mapping_lint" doctor-status-error
                         (format "map lint found %d error(s), %d warning(s)"
                                 (:error_count report)
                                 (:warning_count report)))

              (pos? (:warning_count report))
              (add-check state "mapping_lint" doctor-status-warning
                         (format "map lint found %d warning(s)" (:warning_count report)))

              :else
              (add-check state "mapping_lint" doctor-status-ok "no lint issues")))
          (catch Exception e
            (add-check state "mapping_parse" doctor-status-error (ex-message e))))))))

(defn- bundle-checks
  [state {:keys [bundle-in identity]}]
  (let [bundle-in (some-> bundle-in str/trim)
        identity (some-> identity str/trim)]
    (cond
      (and (str/blank? bundle-in) (str/blank? identity))
      (add-check state "bundle_spec" doctor-status-ok "no --bundle-in/--identity provided; skipping bundle checks")

      :else
      (let [[state bundle-ready?]
            (if (str/blank? bundle-in)
              [state false]
              (let [f (io/file bundle-in)]
                (cond
                  (not (.exists f))
                  [(add-check state "bundle_in" doctor-status-error (format "bundle file not found: %s" bundle-in)) false]

                  (not (.isFile f))
                  [(add-check state "bundle_in" doctor-status-error (format "bundle path is not a regular file: %s" bundle-in)) false]

                  :else
                  [(add-check state "bundle_in" doctor-status-ok bundle-in) true])))
            [state identity-data identity-ready?]
            (if (str/blank? identity)
              [state nil false]
              (let [f (io/file identity)]
                (cond
                  (not (.exists f))
                  [(add-check state "bundle_identity" doctor-status-error (format "identity file not found: %s" identity)) nil false]

                  (not (.isFile f))
                  [(add-check state "bundle_identity" doctor-status-error (format "identity path is not a regular file: %s" identity)) nil false]

                  :else
                  (try
                    (let [loaded-id (bundle/load-identity {:identity-file identity
                                                           :from-stdin? false
                                                           :stdin nil})]
                      [(add-check state "bundle_identity" doctor-status-ok identity) loaded-id true])
                    (catch Exception e
                      [(add-check state "bundle_identity" doctor-status-error (ex-message e)) nil false])))))
            state
            (cond
              (and bundle-ready? identity-ready?)
              (try
                (bundle/validate-bundle-with-identity bundle-in identity-data)
                (add-check state "bundle_decrypt" doctor-status-ok "bundle decrypt validated")
                (catch Exception e
                  (add-check state "bundle_decrypt" doctor-status-error (ex-message e))))

              (and bundle-ready? (str/blank? identity))
              (add-check state "bundle_decrypt" doctor-status-warning "bundle file present but no identity provided; decryptability not verified")

              :else
              state)]
        state))))

(defn- trim-not-blank
  [s]
  (some-> s str str/trim not-empty))

(defn- hex-bytes
  [^bytes b]
  (apply str (map #(format "%02x" (bit-and % 0xff)) b)))

(defn- file-sha256-hex
  [path]
  (let [bytes (Files/readAllBytes (.toPath (io/file path)))
        digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (hex-bytes digest)))

(defn- file-revision
  [path]
  (let [f (io/file path)]
    (if (.exists f)
      [(file-sha256-hex path) true]
      [nil false])))

(defn- fs-remote-bundle-path
  [remote]
  (let [path (trim-not-blank (get remote "path"))
        bundle-path (or (trim-not-blank (get remote "bundle_path")) "vault.age")
        path (some-> path (str/replace #"/+$" ""))]
    (when-not (str/blank? path)
      (if (str/ends-with? path ".age")
        path
        (str path "/" bundle-path)))))

(defn- remote-sync-state-check
  [state cfg remote name transport-ok?]
  (if-not transport-ok?
    state
    (let [type (or (trim-not-blank (get remote "type")) "fs")
          sync-map (if (map? (get cfg "sync")) (get cfg "sync") {})
          sync-entry (if (map? sync-map) (get sync-map name) nil)
          last-seen (some-> sync-entry (get "last_seen_rev") trim-not-blank)]
      (if-not (= "fs" type)
        state
        (let [bundle-path (fs-remote-bundle-path remote)
              [remote-rev has-remote] (if (str/blank? bundle-path)
                                        [nil false]
                                        (file-revision bundle-path))
              check-name (str "remote_" name "_sync_state")
              conflict (sync-state/detect-conflict last-seen remote-rev has-remote)]
          (if (:has-conflict conflict)
            (add-check state check-name doctor-status-warning (or (:message conflict) "remote sync state conflict"))
            (if has-remote
              (add-check state check-name doctor-status-ok "baseline matches remote revision")
              (add-check state check-name doctor-status-ok "no baseline and remote has no bundle yet"))))))))

(defn- remote-checks
  [state cfg cfg-valid?]
  (if-not cfg-valid?
    (add-check state "remote_config" doctor-status-error "cannot validate remotes because config is invalid")
    (let [remotes (if (vector? (get cfg "remotes"))
                    (get cfg "remotes")
                    [])]
      (if (empty? remotes)
        (add-check state "remote_config" doctor-status-ok "no remotes configured; skipping remote checks")
        (let [{:keys [state remote-names]}
              (reduce
                (fn [{:keys [state remote-names]} remote]
                  (let [name (or (trim-not-blank (get remote "name")) "unnamed")
                        type (some-> (or (get remote "type") "fs") str/lower-case str/trim)
                        path (trim-not-blank (get remote "path"))
                        branch (trim-not-blank (get remote "branch"))
                        recipient (trim-not-blank (get remote "recipient"))
                        identity (trim-not-blank (get remote "identity"))
                        [state transport-ok?]
                        (case type
                          "fs"
                          (cond
                            (str/blank? path)
                            [(add-check state (str "remote_" name "_fs_dir") doctor-status-error "fs remote path is empty")
                             false]

                            (.exists (io/file path))
                            [(add-check state (str "remote_" name "_fs_dir") doctor-status-ok path)
                             true]

                            :else
                            [(add-check state (str "remote_" name "_fs_dir") doctor-status-warning (format "fs remote path does not exist yet: %s" path))
                             true])

                          "git"
                          (if (str/blank? path)
                            [(add-check state (str "remote_" name "_git_remote") doctor-status-error "git remote path is empty")
                             false]
                            (let [branch (or branch "main")
                                  res (sh/sh "git" "ls-remote" "--heads" path branch :out :string :err :string)
                                  msg (or (not-empty (str/trim (str (:err res))))
                                          (not-empty (str/trim (str (:out res))))
                                          "git ls-remote failed")]
                              (if (zero? (:exit res))
                                (let [state (add-check state (str "remote_" name "_git_remote") doctor-status-ok path)
                                      state (if (str/blank? (str/trim (str (:out res))))
                                              (add-check state (str "remote_" name "_git_branch") doctor-status-warning
                                                         (format "branch %s not found on remote (may be created on first push)"
                                                                 (pr-str branch)))
                                              (add-check state (str "remote_" name "_git_branch") doctor-status-ok
                                                         (format "branch %s found on remote" (pr-str branch))))]
                                  [state true])
                                [(add-check state (str "remote_" name "_git_remote") doctor-status-error msg)
                                 false])))

                          [(add-check state (str "remote_" name "_type") doctor-status-error (format "unknown remote type %s" (pr-str (or type ""))))
                           false])
                        state (if (str/blank? recipient)
                                (add-check state (str "remote_" name "_recipient") doctor-status-warning "remote recipient is not configured")
                                (add-check state (str "remote_" name "_recipient") doctor-status-ok recipient))
                        state (cond
                                (str/blank? identity)
                                (add-check state (str "remote_" name "_identity") doctor-status-warning "remote identity is not configured")

                                (.exists (io/file identity))
                                (add-check state (str "remote_" name "_identity") doctor-status-ok identity)

                                :else
                                (add-check state (str "remote_" name "_identity") doctor-status-warning (format "remote identity file not found: %s" identity)))
                        state (remote-sync-state-check state cfg remote name transport-ok?)]
                    {:state state
                     :remote-names (conj remote-names name)}))
                {:state state
                 :remote-names #{}}
                remotes)
              sync-map (if (map? (get cfg "sync")) (get cfg "sync") {})
              stale-sync-names (->> (keys sync-map)
                                    (map trim-not-blank)
                                    (remove nil?)
                                    (remove remote-names)
                                    sort)]
          (reduce (fn [state stale-name]
                    (add-check state
                               (str "remote_sync_state_" stale-name)
                               doctor-status-warning
                               "sync baseline exists for unknown remote; remove stale state with `kimen remote rm` or edit config"))
                  state
                  stale-sync-names))))))

(defn run-doctor-checks
  [{:keys [ctx map-path profile bundle-in identity allow-missing-vault?]}]
  (let [state {:checks []
               :error_count 0
               :warning_count 0}
        state (if (and (some-> map-path str/trim not-empty)
                       (some-> profile str/trim not-empty))
                (add-check state "mapping_input" doctor-status-error "use only one of --map or --profile")
                state)
        cfg-path (try
                   (config/resolve-config-path (:config-path ctx))
                   (catch Exception e
                     (str "unavailable:" (ex-message e))))
        state (if (str/starts-with? cfg-path "unavailable:")
                (add-check state "config_path" doctor-status-error cfg-path)
                (add-check state "config_path" doctor-status-ok cfg-path))
        [cfg cfg-valid? cfg-error]
        (try
          (let [[cfg exists?] (config/load-config (:config-path ctx))
                msg (if exists?
                      "config file parsed"
                      "config file not found (defaults apply)")]
            [cfg true (add-check state "config_json" doctor-status-ok msg)])
          (catch Exception e
            [nil false (add-check state "config_json" doctor-status-error (ex-message e))]))
        state (passphrase-source-check cfg-error cfg cfg-valid?)
        vault-path (vault-path/resolve-vault-path ctx nil)
        state (add-check state "vault_path" doctor-status-ok vault-path)
        f (io/file vault-path)
        state
        (cond
          (not (.exists f))
          (if allow-missing-vault?
            (add-check state "vault_file" doctor-status-ok "vault file not found (allowed by --allow-missing-vault)")
            (add-check state "vault_file" doctor-status-error "vault file not found"))

          (not (.isFile f))
          (add-check state "vault_file" doctor-status-error "vault path is not a regular file")

          :else
          (let [state (add-check state "vault_file" doctor-status-ok vault-path)]
            (try
              (let [info (vault-v2/vault-info vault-path)
                    fmt (get info "format_version")
                    kdf (get-in info ["kdf" "name"])]
                (add-check state "vault_metadata" doctor-status-ok (format "format=%s kdf=%s" fmt kdf)))
              (catch Exception e
                (add-check state "vault_metadata" doctor-status-error (ex-message e))))))
        state (mapping-checks state {:map-path map-path :profile profile})
        state (bundle-checks state {:bundle-in bundle-in :identity identity})
        state (remote-checks state cfg cfg-valid?)]
    state))

(defn finalize-report
  [{:keys [checks error_count warning_count]} strict?]
  (let [ok (and (zero? error_count)
                (or (not strict?) (zero? warning_count)))]
    {:ok ok
     :action "doctor"
     :exit_code (if ok 0 27)
     :strict (boolean strict?)
     :error_count error_count
     :warning_count warning_count
     :checks checks}))

(defn render-report-text
  [{:keys [ok strict error_count warning_count checks]}]
  (let [header (format "doctor: %s (strict=%s errors=%d warnings=%d)"
                       (if ok "ok" "failed")
                       strict
                       error_count
                       warning_count)
        lines (for [{:keys [name status message]} checks]
                (format "- [%s:%s] %s" status name message))]
    (str/join "\n" (concat [header] lines))))

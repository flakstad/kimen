(ns kimen.sync-state
  (:require
   [clojure.string :as str]
   [kimen.reason-codes :as reasons]))

(set! *warn-on-reflection* true)

(def conflict-reasons
  #{reasons/reason-remote-disappeared
    reasons/reason-no-local-baseline
    reasons/reason-remote-changed
    reasons/reason-overlapping-changes})

(defn conflict-reason?
  [reason]
  (contains? conflict-reasons reason))

(def ^:private reason->recommended-action
  {reasons/reason-remote-changed "sync_pull"
   reasons/reason-no-local-baseline "sync_pull"
   reasons/reason-remote-disappeared "sync_reset_baseline_or_remote_recreate"
   reasons/reason-remote-lock-present "wait_or_sync_unlock"
   reasons/reason-overlapping-changes "manual_reconcile"})

(defn recommended-action-for-reason
  [reason]
  (get reason->recommended-action (some-> reason str/trim)))

(def ^:private error-reason-rules
  [{:reason reasons/reason-invalid-stale-threshold
    :all ["--stale-threshold must be >= 0"]}
   {:reason reasons/reason-conflicting-check-and-dry-run
    :all ["--check and --dry-run cannot be used together"]}
   {:reason reasons/reason-empty-remote-name
    :all ["empty remote name"]}
   {:reason reasons/reason-remote-name-mismatch
    :all ["remote name mismatch between arg and --remote"]}
   {:reason reasons/reason-invalid-remote-name
    :all ["invalid remote name"]}
   {:reason reasons/reason-remote-exists
    :all ["remote " "already exists"]}
   {:reason reasons/reason-remote-not-found-from-env
    :all ["remote " "not found (from kimen_remote)"]}
   {:reason reasons/reason-remote-not-found
    :all ["remote " "not found"]}
   {:reason reasons/reason-no-remotes-configured
    :all ["no remotes configured"]}
   {:reason reasons/reason-multiple-remotes-configured
    :all ["multiple remotes configured"]}
   {:reason reasons/reason-missing-path
    :all ["--path is required"]}
   {:reason reasons/reason-git-fields-require-git-type
    :all ["--branch/--bundle-path are only valid for --type git"]}
   {:reason reasons/reason-recipient-derivation-failed
    :all ["derive recipient from identity"]}
   {:reason reasons/reason-invalid-take
    :all ["--take must be one of"]}
   {:reason reasons/reason-invalid-reset-baseline-mode
    :all ["choose exactly one mode: --to-remote, --clear, or --rev"]}
   {:reason reasons/reason-reset-baseline-confirmation-required
    :any ["refusing to reset baseline without --yes"
          "sync reset-baseline requires --yes"]}
   {:reason reasons/reason-remote-bundle-missing-for-baseline
    :all ["remote bundle is missing; cannot set baseline to remote"]}
   {:reason reasons/reason-reconcile-baseline-missing
    :any ["cannot resolve conflicts without baseline key hashes"
          "cannot reconcile without baseline key hashes"]}
   {:reason reasons/reason-invalid-if-older-than
    :all ["--if-older-than must be >= 0"]}
   {:reason reasons/reason-unlock-requires-fs-remote
    :all ["sync unlock is only supported for fs remotes"]}
   {:reason reasons/reason-lock-too-new
    :all ["refusing to unlock" "lock is only"]}
   {:reason reasons/reason-unlock-confirmation-required
    :all ["refusing to remove lock without --yes"]}
   {:reason reasons/reason-missing-backup
    :all ["--backup is required"]}
   {:reason reasons/reason-invalid-lock-wait
    :all ["--lock-wait must be >= 0"]}
   {:reason reasons/reason-invalid-break-stale-lock-after
    :all ["--break-stale-lock-after must be >= 0"]}
   {:reason reasons/reason-conflicting-dry-run-lock-flags
    :all ["--dry-run cannot be combined with --lock-wait/--break-stale-lock-after"]}
   {:reason reasons/reason-remote-recipient-missing
    :all ["remote recipient is not configured"]}
   {:reason reasons/reason-lock-flags-require-fs-remote
    :all ["--lock-wait/--break-stale-lock-after are only supported for fs remotes"]}
   {:reason reasons/reason-local-vault-missing
    :all ["local vault file not found"]}
   {:reason reasons/reason-local-vault-missing-after-resolve
    :all ["local vault missing after sync resolve"]}
   {:reason reasons/reason-local-vault-missing-after-pull
    :all ["local vault missing after pull"]}
   {:reason reasons/reason-local-vault-disappeared-before-baseline-update
    :all ["local vault disappeared before baseline update"]}
   {:reason reasons/reason-remote-identity-missing
    :all ["remote identity is not configured"]}
   {:reason reasons/reason-remote-bundle-missing
    :all ["remote bundle is missing"]}
   {:reason reasons/reason-unsupported-remote-type
    :all ["unsupported remote type"]}
   {:reason reasons/reason-unknown-preflight-check
    :all ["unknown preflight check"]}
   {:reason reasons/reason-unsupported-preflight-check
    :all ["unsupported preflight check"]}
   {:reason reasons/reason-no-preflight-checks-selected
    :all ["no preflight checks selected"]}
   {:reason reasons/reason-resolve-keys-not-conflicts
    :all ["keys are not current conflict keys"]}
   {:reason reasons/reason-sync-status-empty-payload
    :all ["sync status returned empty payload"]}
   {:reason reasons/reason-sync-status-decode-failed
    :all ["decode sync status payload"]}
   {:reason reasons/reason-sync-status-missing-action
    :all ["sync status payload missing action"]}])

(defn- includes-all?
  [s needles]
  (every? #(str/includes? s %) needles))

(defn infer-error-reason-from-message
  [message]
  (let [msg (some-> message str/lower-case str/trim)]
    (when-not (str/blank? msg)
      (some (fn [{:keys [reason all any]}]
              (cond
                (seq all) (when (includes-all? msg all) reason)
                (seq any) (when (some #(str/includes? msg %) any) reason)
                :else nil))
            error-reason-rules))))

(defn detect-conflict
  [last-seen remote-rev has-remote]
  (let [last-seen (some-> last-seen str str/trim)
        last-seen (when-not (str/blank? last-seen) last-seen)
        remote-rev (some-> remote-rev str str/trim)
        remote-rev (when-not (str/blank? remote-rev) remote-rev)]
    (cond
      (and (not has-remote) (nil? last-seen))
      {:has-conflict false}

      (and (not has-remote) (some? last-seen))
      {:has-conflict true
       :reason reasons/reason-remote-disappeared
       :message (format "remote bundle disappeared since last sync (expected rev %s)" last-seen)
       :expected-rev last-seen}

      (and has-remote (nil? last-seen))
      {:has-conflict true
       :reason reasons/reason-no-local-baseline
       :message (format "remote has data (rev %s) but no local baseline; run `kimen sync pull` first"
                        (or remote-rev "unknown"))
       :actual-rev remote-rev}

      (and has-remote (not= last-seen remote-rev))
      {:has-conflict true
       :reason reasons/reason-remote-changed
       :message (format "remote changed (expected rev %s, found %s); run `kimen sync pull`, re-apply changes, then push"
                        last-seen
                        (or remote-rev "unknown"))
       :expected-rev last-seen
       :actual-rev remote-rev}

      :else
      {:has-conflict false})))

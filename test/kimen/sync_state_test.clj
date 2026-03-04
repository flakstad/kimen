(ns kimen.sync-state-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [kimen.reason-codes :as reasons]
    [kimen.sync-state :as sync-state]))

(deftest detect-conflict-cases
  (testing "no baseline and no remote data is clean"
    (is (= {:has-conflict false}
           (sync-state/detect-conflict nil nil false))))

  (testing "remote disappeared after baseline"
    (let [res (sync-state/detect-conflict "abc123" nil false)]
      (is (= true (:has-conflict res)))
      (is (= reasons/reason-remote-disappeared (:reason res)))
      (is (= "abc123" (:expected-rev res)))))

  (testing "remote exists with no local baseline"
    (let [res (sync-state/detect-conflict nil "def456" true)]
      (is (= true (:has-conflict res)))
      (is (= reasons/reason-no-local-baseline (:reason res)))
      (is (= "def456" (:actual-rev res)))))

  (testing "remote changed since baseline"
    (let [res (sync-state/detect-conflict "abc123" "def456" true)]
      (is (= true (:has-conflict res)))
      (is (= reasons/reason-remote-changed (:reason res)))
      (is (= "abc123" (:expected-rev res)))
      (is (= "def456" (:actual-rev res)))))

  (testing "baseline and remote revision match"
    (is (= {:has-conflict false}
           (sync-state/detect-conflict "same" "same" true))))

  (testing "blank and whitespace values are normalized"
    (let [res (sync-state/detect-conflict "  " "  rev  " true)]
      (is (= true (:has-conflict res)))
      (is (= reasons/reason-no-local-baseline (:reason res)))
      (is (= "rev" (:actual-rev res))))))

(deftest recommended-action-mapping
  (is (= "sync_pull"
         (sync-state/recommended-action-for-reason reasons/reason-remote-changed)))
  (is (= "sync_pull"
         (sync-state/recommended-action-for-reason reasons/reason-no-local-baseline)))
  (is (= "sync_reset_baseline_or_remote_recreate"
         (sync-state/recommended-action-for-reason reasons/reason-remote-disappeared)))
  (is (= "wait_or_sync_unlock"
         (sync-state/recommended-action-for-reason reasons/reason-remote-lock-present)))
  (is (= "manual_reconcile"
         (sync-state/recommended-action-for-reason reasons/reason-overlapping-changes)))
  (is (nil? (sync-state/recommended-action-for-reason "unknown_reason"))))

(deftest infer-error-reason-from-message-cases
  (is (= reasons/reason-invalid-stale-threshold
         (sync-state/infer-error-reason-from-message "--stale-threshold must be >= 0")))
  (is (= reasons/reason-remote-not-found-from-env
         (sync-state/infer-error-reason-from-message "remote origin not found (from KIMEN_REMOTE)")))
  (is (= reasons/reason-remote-not-found
         (sync-state/infer-error-reason-from-message "remote origin not found")))
  (is (= reasons/reason-lock-too-new
         (sync-state/infer-error-reason-from-message "refusing to unlock: lock is only 3s old")))
  (is (= reasons/reason-reset-baseline-confirmation-required
         (sync-state/infer-error-reason-from-message "sync reset-baseline requires --yes")))
  (is (= reasons/reason-sync-status-missing-action
         (sync-state/infer-error-reason-from-message "sync status payload missing action")))
  (is (nil? (sync-state/infer-error-reason-from-message nil)))
  (is (nil? (sync-state/infer-error-reason-from-message "unmapped message"))))

(deftest conflict-reason-predicate
  (is (true? (sync-state/conflict-reason? reasons/reason-remote-changed)))
  (is (true? (sync-state/conflict-reason? reasons/reason-overlapping-changes)))
  (is (false? (sync-state/conflict-reason? reasons/reason-sync-failed))))

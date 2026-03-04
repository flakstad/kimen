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

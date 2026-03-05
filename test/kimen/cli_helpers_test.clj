(ns kimen.cli-helpers-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [kimen.cli.parse :as parse]
   [kimen.cli.usage :as usage]
   [clojure.string :as str]))

(deftest parse-flag-value-cases
  (testing "flag value via separate arg"
    (let [[v rest-args err] (parse/parse-flag-value ["--vault" "/tmp/vault.db" "tail"] "--vault")]
      (is (= "/tmp/vault.db" v))
      (is (= ["tail"] (vec rest-args)))
      (is (nil? err))))
  (testing "flag value via inline assignment"
    (let [[v rest-args err] (parse/parse-flag-value ["--vault=/tmp/vault.db" "tail"] "--vault")]
      (is (= "/tmp/vault.db" v))
      (is (= ["tail"] (vec rest-args)))
      (is (nil? err))))
  (testing "missing required value reports error"
    (let [[v rest-args err] (parse/parse-flag-value ["--vault"] "--vault")]
      (is (nil? v))
      (is (= ["--vault"] (vec rest-args)))
      (is (= "missing value for --vault" err)))))

(deftest parse-duration-ms-cases
  (is (= [30000 nil] (parse/parse-duration-ms "30s")))
  (is (= [300000 nil] (parse/parse-duration-ms "5m")))
  (is (= [42 nil] (parse/parse-duration-ms "42")))
  (is (= [nil "missing duration value"] (parse/parse-duration-ms "")))
  (let [[v err] (parse/parse-duration-ms "x3")]
    (is (nil? v))
    (is (str/includes? err "invalid duration"))))

(deftest parse-json-only-opts-cases
  (is (= [{:json? true} nil] (parse/parse-json-only-opts ["--json"])))
  (is (= [{:json? false} "unknown flag --bad"] (parse/parse-json-only-opts ["--bad"])))
  (is (= [{:json? false} "unexpected argument \"extra\""] (parse/parse-json-only-opts ["extra"]))))

(deftest split-before-double-dash-cases
  (is (= [["sync" "push"] ["echo" "ok"]]
         (mapv vec (parse/split-before-double-dash ["sync" "push" "--" "echo" "ok"]))))
  (is (= [["sync" "push"] []]
         (mapv vec (parse/split-before-double-dash ["sync" "push"])))))

(deftest usage-help-topics-include-known-commands
  (let [completion "completion help text"
        topics (usage/help-topics completion)]
    (is (= completion (get topics "completion")))
    (is (str/includes? (get topics "version") "kimen version"))
    (is (str/includes? usage/usage "Commands:"))
    (is (str/includes? usage/secret-usage "kimen secret set <name>"))))

(ns kimen.api-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [kimen.api :as api]))

(deftest run-returns-cli-result
  (let [{:keys [exit-code stdout stderr]} (api/run ["version" "--json"])]
    (is (= 0 exit-code))
    (is (nil? stderr))
    (is (str/includes? stdout "\"action\":\"version\""))))

(deftest run-allows-context-overrides
  (let [ctx {:read-file (fn [_] "env API_KEY=api_key\n")}
        {:keys [exit-code stdout stderr]}
        (api/run ctx ["map" "lint" "--map" "dev.kmap" "--json"])]
    (is (= 0 exit-code))
    (is (nil? stderr))
    (is (str/includes? stdout "\"action\":\"map_lint\""))))

(deftest run-bang-emits-stdout-and-stderr
  (let [err (java.io.StringWriter.)
        out (with-out-str
              (binding [*err* err]
                (is (= 0 (api/run! ["version" "--json"]))))
              (binding [*err* err]
                (is (= 1 (api/run! ["nope"])))))
        err-out (str err)]
    (is (str/includes? out "\"action\":\"version\""))
    (is (str/includes? err-out "unknown command"))))

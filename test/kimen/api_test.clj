(ns kimen.api-test
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [kimen.api :as api]
   [kimen.exit-code :as exit-code]))

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

(deftest edn-output-flag-produces-machine-edn
  (let [{:keys [exit-code stdout stderr]} (api/run ["version" "--edn"])
        payload (edn/read-string stdout)]
    (is (= 0 exit-code))
    (is (nil? stderr))
    (is (= true (:ok payload)))
    (is (= "version" (:action payload)))))

(deftest edn-output-flag-converts-error-payload
  (let [{:keys [exit-code stdout stderr]} (api/run ["map" "lint" "--edn"])
        payload (edn/read-string stdout)]
    (is (= exit-code/code-map-lint-failed exit-code))
    (is (nil? stderr))
    (is (= false (:ok payload)))
    (is (= "map_lint" (:action payload)))
    (is (= "invalid_input" (:code (first (:issues payload)))))))

(deftest edn-output-flag-does-not-consume-child-args
  (let [{:keys [exit-code stdout stderr]}
        (api/run ["run" "--dry-run" "--env" "API_KEY=const:shh" "--edn" "--" "echo" "--edn"])
        payload (edn/read-string stdout)]
    (is (= 0 exit-code))
    (is (nil? stderr))
    (is (= "plan" (:action payload)))
    (is (= ["echo" "--edn"] (vec (:command payload))))))

(deftest json-and-edn-flags-conflict
  (let [{:keys [exit-code stdout stderr]} (api/run ["version" "--json" "--edn"])]
    (is (= 1 exit-code))
    (is (nil? stdout))
    (is (str/includes? stderr "cannot use both --json and --edn"))))

(deftest leading-double-dash-still-supports-edn
  (let [{:keys [exit-code stdout stderr]} (api/run ["--" "version" "--edn"])
        payload (edn/read-string stdout)]
    (is (= 0 exit-code))
    (is (nil? stderr))
    (is (= "version" (:action payload)))))

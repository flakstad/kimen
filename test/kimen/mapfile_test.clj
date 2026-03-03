(ns kimen.mapfile-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [kimen.mapfile :as mapfile]))

(deftest parse-string-parses-core-kinds
  (let [m (mapfile/parse-string (str "env API_KEY=secret:api_key\n"
                                    "file conf/api.txt=api_key\n"
                                    "stdin const:hello\n"
                                    "envpath API_KEY_PATH=conf/api.txt\n"))]
    (is (= [{:var "API_KEY" :name "secret:api_key"}]
           (get-in m [:request :envs])))
    (is (= [{:rel-path "conf/api.txt" :name "api_key"}]
           (get-in m [:request :files])))
    (is (= "const:hello" (get-in m [:request :stdin])))
    (is (= [{:var "API_KEY_PATH" :rel-path "conf/api.txt"}]
           (:env-paths m)))))

(deftest parse-string-rejects-invalid-relative-path
  (is (thrown-with-msg?
        clojure.lang.ExceptionInfo
        #"invalid relative path"
        (mapfile/parse-string "file ../bad=secret\n"))))

(deftest parse-string-supports-inline-comments
  (let [m (mapfile/parse-string "env API_KEY=api_key  # this is ignored\n")]
    (is (= [{:var "API_KEY" :name "api_key"}]
           (get-in m [:request :envs])))))

(deftest profile-name-validation
  (testing "accepted"
    (is (true? (mapfile/valid-profile-name? "dev")))
    (is (true? (mapfile/valid-profile-name? "prod-us-1"))))
  (testing "rejected"
    (is (false? (mapfile/valid-profile-name? "")))
    (is (false? (mapfile/valid-profile-name? "../bad")))))

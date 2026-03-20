(ns kimen.mapfile-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [kimen.mapfile :as mapfile]))

(set! *warn-on-reflection* true)

(defn- with-system-property
  [k v f]
  (let [prev (System/getProperty k)]
    (try
      (System/setProperty k v)
      (f)
      (finally
        (if (some? prev)
          (System/setProperty k prev)
          (System/clearProperty k))))))

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

(deftest resolve-profile
  (testing "reads from user home config fallback"
    (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-mapfile-test" (make-array java.nio.file.attribute.FileAttribute 0)))
          home-dir (str (.getPath dir) "/home")
          profile-dir (str home-dir "/.config/kimen/profiles")
          profile-path (str profile-dir "/linje-prod.kmap")]
      (.mkdirs (java.io.File. profile-dir))
      (spit profile-path "env API_KEY=api_key\n")
      (with-system-property
        "user.home"
        home-dir
        (fn []
          (is (= profile-path (mapfile/resolve-profile "linje-prod")))))))

  (testing "invalid names are rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"invalid profile name"
          (mapfile/resolve-profile "../bad"))))

  (testing "missing profiles are reported as invalid profile name"
    (let [dir (.toFile (java.nio.file.Files/createTempDirectory "kimen-mapfile-test" (make-array java.nio.file.attribute.FileAttribute 0)))
          home-dir (str (.getPath dir) "/home")]
      (with-system-property
        "user.home"
        home-dir
        (fn []
          (is (thrown-with-msg?
                clojure.lang.ExceptionInfo
                #"not found"
                (mapfile/resolve-profile "does-not-exist"))))))))

(ns kimen.bundle-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [kimen.bundle :as bundle]
    [kimen.reason-codes :as reasons])
  (:import
    [java.nio.file Files]
    [java.util Arrays]))

(defn- tmp-dir
  []
  (.toFile (Files/createTempDirectory "kimen-bundle-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest bundle-seal-open-roundtrip
  (let [dir (tmp-dir)
        vault-path (str (.getPath dir) "/vault.db")
        bundle-path (str (.getPath dir) "/vault.age")
        out-path (str (.getPath dir) "/vault.out.db")
        id-path (str (.getPath dir) "/ci.agekey")
        input (.getBytes "vault-bytes" "UTF-8")]
    (Files/write (.toPath (java.io.File. vault-path)) input (make-array java.nio.file.OpenOption 0))
    (let [{:keys [recipient]} (bundle/generate-identity-file id-path false)
          loaded (bundle/load-identity {:identity-file id-path
                                        :from-stdin? false
                                        :stdin nil})]
      (is (= recipient (bundle/recipient-for-identity loaded)))
      (bundle/seal-vault-file vault-path bundle-path [recipient])
      (bundle/open-to-vault-file bundle-path out-path loaded false)
      (is (Arrays/equals input
                         (Files/readAllBytes (.toPath (java.io.File. out-path))))))))

(deftest bundle-overwrite-protections
  (let [dir (tmp-dir)
        id-path (str (.getPath dir) "/ci.agekey")]
    (bundle/generate-identity-file id-path false)
    (let [err (try
                (bundle/generate-identity-file id-path false)
                nil
                (catch Exception e
                  e))]
      (is (some? err))
      (is (= reasons/reason-identity-exists (-> err ex-data :reason))))))

(deftest bundle-validation-fails-for-wrong-identity
  (let [dir (tmp-dir)
        vault-path (str (.getPath dir) "/vault.db")
        bundle-path (str (.getPath dir) "/vault.age")
        id1-path (str (.getPath dir) "/id1.agekey")
        id2-path (str (.getPath dir) "/id2.agekey")
        input (.getBytes "vault-bytes" "UTF-8")]
    (Files/write (.toPath (java.io.File. vault-path)) input (make-array java.nio.file.OpenOption 0))
    (let [{:keys [recipient]} (bundle/generate-identity-file id1-path false)
          _ (bundle/generate-identity-file id2-path false)
          id1 (bundle/load-identity {:identity-file id1-path :from-stdin? false :stdin nil})
          id2 (bundle/load-identity {:identity-file id2-path :from-stdin? false :stdin nil})]
      (bundle/seal-vault-file vault-path bundle-path [recipient])
      (is (true? (bundle/validate-bundle-with-identity bundle-path id1)))
      (let [err (try
                  (bundle/validate-bundle-with-identity bundle-path id2)
                  nil
                  (catch Exception e
                    e))]
        (is (some? err))
        (is (= reasons/reason-bundle-failed (-> err ex-data :reason)))))))

(deftest load-identity-rejects-standard-age-secret-keys
  (let [standard-key "AGE-SECRET-KEY-1JD0YW2RCWGT0WFSVD6C4XS5HNAE50DMNV3P3F0508ZM6ZN55HTFSMS3QHC"
        err (try
              (bundle/load-identity {:identity-file nil
                                     :from-stdin? true
                                     :stdin (java.io.StringReader. (str standard-key "\n"))})
              nil
              (catch Exception e
                e))]
    (is (some? err))
    (is (= reasons/reason-no-identity-found (-> err ex-data :reason)))))

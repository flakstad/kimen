(ns kimen.vault-v2-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [kimen.json :as json]
   [kimen.reason-codes :as reasons]
   [kimen.vault.v2 :as vault-v2])
  (:import
   [java.nio.file Files]
   [java.util Base64]
   [javax.crypto Cipher SecretKeyFactory]
   [javax.crypto.spec GCMParameterSpec PBEKeySpec SecretKeySpec]))

(set! *warn-on-reflection* true)

(defn- b64-encode
  [^bytes b]
  (.encodeToString (Base64/getEncoder) b))

(defn- ^"[B" b64-decode
  [^String s]
  (.decode (Base64/getDecoder) s))

(defn- random-vault-path
  []
  (let [dir (.toFile (Files/createTempDirectory "kimen-vault-test" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (str (.getPath dir) "/vault.db")))

(defn- ex-reason
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (:reason (ex-data e)))))

(defn- legacy-derive-key
  [passphrase ^bytes salt iterations key-len-bytes]
  (let [spec (PBEKeySpec. (.toCharArray (str passphrase))
                          salt
                          (int iterations)
                          (int (* 8 key-len-bytes)))
        skf (SecretKeyFactory/getInstance "PBKDF2WithHmacSHA256")]
    (try
      (let [secret (.generateSecret skf spec)]
        (.getEncoded secret))
      (finally
        (.clearPassword spec)))))

(defn- legacy-encrypt-bytes
  [^bytes key ^bytes plaintext ^bytes nonce]
  (let [cipher (Cipher/getInstance "AES/GCM/NoPadding")
        key-spec (SecretKeySpec. key "AES")
        gcm (GCMParameterSpec. 128 nonce)]
    (.init cipher Cipher/ENCRYPT_MODE key-spec gcm)
    (.updateAAD cipher (.getBytes "kimen-v2|vault" "UTF-8"))
    (.doFinal cipher plaintext)))

(defn- byte-array-from
  [values]
  (byte-array (map byte values)))

(defn- legacy-vault-payload
  [passphrase secrets]
  (let [iterations 210000
        key-len 32
        salt (byte-array-from (range 1 17))
        nonce (byte-array-from (range 21 33))
        key (legacy-derive-key passphrase salt iterations key-len)
        ^String plaintext-json (json/write-str {"secrets" secrets})
        plaintext (.getBytes plaintext-json "UTF-8")
        ciphertext (legacy-encrypt-bytes key plaintext nonce)]
    {"format_version" "kimen-v2"
     "kdf" {"name" "pbkdf2-hmac-sha256"
             "iterations" iterations
             "key_len" key-len
             "salt_b64" (b64-encode salt)}
     "enc" {"cipher" "aes-gcm"
             "nonce_b64" (b64-encode nonce)
             "aad" "kimen-v2|vault"}
     "ciphertext_b64" (b64-encode ciphertext)}))

(deftest lifecycle-and-rekey
  (let [vault-path (random-vault-path)
        old-pass "old-passphrase"
        new-pass "new-passphrase"]
    (is (= vault-path (vault-v2/init-vault! vault-path old-pass)))
    (is (= [] (vault-v2/list-secret-names vault-path old-pass)))

    (vault-v2/set-secret! vault-path old-pass "api_key" "value")
    (is (= ["api_key"] (vault-v2/list-secret-names vault-path old-pass)))
    (is (= "value" (:value (vault-v2/get-secret vault-path old-pass "api_key"))))

    (vault-v2/rename-secret! vault-path old-pass "api_key" "api_key2")
    (is (= ["api_key2"] (vault-v2/list-secret-names vault-path old-pass)))
    (is (= reasons/reason-secret-not-found
           (ex-reason #(vault-v2/get-secret vault-path old-pass "api_key"))))

    (vault-v2/delete-secret! vault-path old-pass "api_key2")
    (is (= [] (vault-v2/list-secret-names vault-path old-pass)))

    (vault-v2/set-secret! vault-path old-pass "rekey_probe" "probe")
    (is (= vault-path (vault-v2/rekey-vault! vault-path old-pass new-pass)))
    (is (= reasons/reason-wrong-passphrase
           (ex-reason #(vault-v2/list-secret-names vault-path old-pass))))
    (is (= ["rekey_probe"] (vault-v2/list-secret-names vault-path new-pass)))))

(deftest wrong-passphrase-on-open
  (let [vault-path (random-vault-path)
        passphrase "correct-passphrase"]
    (vault-v2/init-vault! vault-path passphrase)
    (is (= reasons/reason-wrong-passphrase
           (ex-reason #(vault-v2/open-vault vault-path "incorrect-passphrase"))))))

(deftest tampered-wrapped-dek-is-rejected
  (let [vault-path (random-vault-path)
        passphrase "passphrase"]
    (vault-v2/init-vault! vault-path passphrase)
    (vault-v2/set-secret! vault-path passphrase "api_key" "value")

    (let [payload (json/read-str (slurp vault-path))
          wrapped (get-in payload ["kdf" "wrapped_dek_b64"])
          wrapped-bytes (b64-decode wrapped)
          idx (dec (alength wrapped-bytes))
          original (bit-and (aget wrapped-bytes idx) 0xFF)]
      (aset-byte wrapped-bytes idx (unchecked-byte (bit-xor original 0xFF)))
      (spit vault-path (str (json/write-str (assoc-in payload ["kdf" "wrapped_dek_b64"]
                                                       (b64-encode wrapped-bytes))) "\n")))

    (is (= reasons/reason-wrong-passphrase
           (ex-reason #(vault-v2/open-vault vault-path passphrase))))))

(deftest tampered-ciphertext-is-rejected
  (let [vault-path (random-vault-path)
        passphrase "passphrase"]
    (vault-v2/init-vault! vault-path passphrase)
    (vault-v2/set-secret! vault-path passphrase "api_key" "value")

    (let [payload (json/read-str (slurp vault-path))
          ciphertext (b64-decode (get payload "ciphertext_b64"))
          idx (dec (alength ciphertext))
          original (bit-and (aget ciphertext idx) 0xFF)]
      (aset-byte ciphertext idx (unchecked-byte (bit-xor original 0xFF)))
      (spit vault-path (str (json/write-str (assoc payload "ciphertext_b64" (b64-encode ciphertext))) "\n")))

    (is (= reasons/reason-wrong-passphrase
           (ex-reason #(vault-v2/open-vault vault-path passphrase))))))

(deftest invalid-empty-wrapped-dek-metadata-is-rejected
  (let [vault-path (random-vault-path)
        passphrase "passphrase"]
    (vault-v2/init-vault! vault-path passphrase)
    (let [payload (json/read-str (slurp vault-path))]
      (spit vault-path (str (json/write-str (assoc-in payload ["kdf" "wrapped_dek_b64"] "")) "\n")))
    (is (= reasons/reason-invalid-vault-file
           (ex-reason #(vault-v2/open-vault vault-path passphrase))))))

(deftest legacy-vault-read-and-upgrade
  (let [vault-path (random-vault-path)
        passphrase "legacy-passphrase"
        legacy-secret {"legacy_key"
                       {"name" "legacy_key"
                        "created_at" "2025-01-01T00:00:00Z"
                        "updated_at" "2025-01-01T00:00:00Z"
                        "value_b64" (b64-encode (.getBytes "legacy-value" "UTF-8"))}}
        legacy-file (legacy-vault-payload passphrase legacy-secret)]
    (spit vault-path (str (json/write-str legacy-file) "\n"))

    (is (= ["legacy_key"] (vault-v2/list-secret-names vault-path passphrase)))
    (is (= "legacy-value" (:value (vault-v2/get-secret vault-path passphrase "legacy_key"))))

    (vault-v2/set-secret! vault-path passphrase "new_key" "new-value")

    (let [upgraded (json/read-str (slurp vault-path))]
      (is (string? (get-in upgraded ["kdf" "wrapped_dek_b64"])))
      (is (not (str/blank? (get-in upgraded ["kdf" "wrapped_dek_b64"])))))

    (is (= "legacy-value" (:value (vault-v2/get-secret vault-path passphrase "legacy_key"))))
    (is (= "new-value" (:value (vault-v2/get-secret vault-path passphrase "new_key"))))))

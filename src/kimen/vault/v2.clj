(ns kimen.vault.v2
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [kimen.json :as json]
    [kimen.reason-codes :as reasons])
  (:import
    [java.security SecureRandom]
    [java.time Instant]
    [java.util Base64]
    [javax.crypto Cipher SecretKeyFactory]
    [javax.crypto.spec GCMParameterSpec PBEKeySpec SecretKeySpec]
    [java.nio.file Files Path Paths StandardCopyOption]
    [java.nio.file.attribute PosixFilePermission]))

(def format-version "kimen-v2")
(def kdf-name "pbkdf2-hmac-sha256")
(def cipher-name "aes-gcm")
(def aad "kimen-v2|vault")
(def default-iterations 210000)
(def default-key-len-bytes 32)
(def default-nonce-len 12)
(def default-salt-len 16)

(defn- fail!
  [reason message]
  (throw (ex-info message {:reason reason})))

(defn- now-iso
  []
  (str (Instant/now)))

(defn- b64-encode
  [^bytes b]
  (.encodeToString (Base64/getEncoder) b))

(defn- b64-decode
  [^String s]
  (try
    (.decode (Base64/getDecoder) s)
    (catch Exception _
      (fail! reasons/reason-invalid-vault-file "invalid base64 payload in vault file"))))

(defn- random-bytes
  [n]
  (let [b (byte-array n)
        rng (SecureRandom.)]
    (.nextBytes rng b)
    b))

(defn- derive-key
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

(defn- encrypt-bytes
  [^bytes key ^bytes plaintext ^bytes nonce]
  (let [cipher (Cipher/getInstance "AES/GCM/NoPadding")
        key-spec (SecretKeySpec. key "AES")
        gcm (GCMParameterSpec. 128 nonce)]
    (.init cipher Cipher/ENCRYPT_MODE key-spec gcm)
    (.updateAAD cipher (.getBytes aad "UTF-8"))
    (.doFinal cipher plaintext)))

(defn- decrypt-bytes
  [^bytes key ^bytes ciphertext ^bytes nonce]
  (try
    (let [cipher (Cipher/getInstance "AES/GCM/NoPadding")
          key-spec (SecretKeySpec. key "AES")
          gcm (GCMParameterSpec. 128 nonce)]
      (.init cipher Cipher/DECRYPT_MODE key-spec gcm)
      (.updateAAD cipher (.getBytes aad "UTF-8"))
      (.doFinal cipher ciphertext))
    (catch Exception e
      (let [class-name (.getName (class e))
            msg (some-> (.getMessage e) str/lower-case)]
        (if (or (str/includes? class-name "AEADBadTagException")
                (and msg (or (str/includes? msg "tag mismatch")
                             (str/includes? msg "mac check"))))
          (fail! reasons/reason-wrong-passphrase "wrong passphrase")
          (fail! reasons/reason-invalid-vault-file "invalid vault ciphertext"))))))

(defn- empty-vault-data
  []
  {"secrets" {}})

(defn- ensure-parent-dir!
  [path]
  (let [f (io/file path)
        parent (.getParentFile f)]
    (when (and parent (not (.exists parent)))
      (when-not (.mkdirs parent)
        (fail! reasons/reason-vault-failed (format "failed to create vault directory %s" (.getPath parent)))))))

(defn- set-posix-600!
  [^Path p]
  (try
    (Files/setPosixFilePermissions
      p
      #{PosixFilePermission/OWNER_READ PosixFilePermission/OWNER_WRITE})
    (catch Exception _ nil)))

(defn- write-file-atomic!
  [path content]
  (ensure-parent-dir! path)
  (let [target (Paths/get path (make-array String 0))
        parent (or (.getParent target) (Paths/get "." (make-array String 0)))
        tmp (Files/createTempFile parent "vault." ".tmp" (make-array java.nio.file.attribute.FileAttribute 0))]
    (spit (.toFile tmp) (str content "\n"))
    (set-posix-600! tmp)
    (try
      (Files/move tmp target
                  (into-array StandardCopyOption
                              [StandardCopyOption/REPLACE_EXISTING
                               StandardCopyOption/ATOMIC_MOVE]))
      (catch Exception _
        (Files/move tmp target
                    (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))))

(defn- validate-vault-outer
  [m]
  (when-not (map? m)
    (fail! reasons/reason-invalid-vault-file "invalid vault file"))
  (when-not (= format-version (get m "format_version"))
    (fail! reasons/reason-invalid-vault-file "invalid vault format"))
  (let [kdf (get m "kdf")
        enc (get m "enc")
        ciphertext-b64 (get m "ciphertext_b64")]
    (when-not (map? kdf)
      (fail! reasons/reason-invalid-vault-file "missing kdf metadata"))
    (when-not (map? enc)
      (fail! reasons/reason-invalid-vault-file "missing encryption metadata"))
    (when-not (string? ciphertext-b64)
      (fail! reasons/reason-invalid-vault-file "missing ciphertext"))
    {:kdf kdf
     :enc enc
     :ciphertext-b64 ciphertext-b64}))

(defn- decode-vault
  [m passphrase]
  (let [{:keys [kdf enc ciphertext-b64]} (validate-vault-outer m)
        kdf-name* (get kdf "name")
        iterations (long (or (get kdf "iterations") 0))
        key-len (long (or (get kdf "key_len") 0))
        salt (b64-decode (or (get kdf "salt_b64") ""))
        cipher* (get enc "cipher")
        nonce (b64-decode (or (get enc "nonce_b64") ""))]
    (when-not (= kdf-name* kdf-name)
      (fail! reasons/reason-invalid-vault-file "unsupported kdf"))
    (when-not (= cipher* cipher-name)
      (fail! reasons/reason-invalid-vault-file "unsupported cipher"))
    (when (or (<= iterations 0)
              (<= key-len 0)
              (not= key-len default-key-len-bytes)
              (< (alength salt) 8)
              (< (alength nonce) 8))
      (fail! reasons/reason-invalid-vault-file "invalid vault crypto metadata"))
    (let [key (derive-key passphrase salt iterations key-len)
          plaintext (decrypt-bytes key (b64-decode ciphertext-b64) nonce)
          decoded (json/read-str (String. plaintext "UTF-8"))]
      (when-not (map? decoded)
        (fail! reasons/reason-invalid-vault-file "invalid vault payload"))
      {:meta {"kdf" kdf
              "enc" {"cipher" cipher-name}
              "format_version" format-version}
       :data (let [secrets (get decoded "secrets")]
               {"secrets" (if (map? secrets) secrets {})})})))

(defn- encode-vault
  [data passphrase kdf]
  (let [salt (if-let [v (get kdf "salt_b64")]
               (b64-decode v)
               (random-bytes default-salt-len))
        iterations (long (or (get kdf "iterations") default-iterations))
        key-len (long (or (get kdf "key_len") default-key-len-bytes))
        key (derive-key passphrase salt iterations key-len)
        nonce (random-bytes default-nonce-len)
        plaintext (.getBytes (json/write-str {"secrets" (or (get data "secrets") {})}) "UTF-8")
        ciphertext (encrypt-bytes key plaintext nonce)]
    {"format_version" format-version
     "kdf" {"name" kdf-name
             "iterations" iterations
             "key_len" key-len
             "salt_b64" (b64-encode salt)}
     "enc" {"cipher" cipher-name
             "nonce_b64" (b64-encode nonce)
             "aad" aad}
     "ciphertext_b64" (b64-encode ciphertext)}))

(defn- load-vault-file
  [path]
  (let [f (io/file path)]
    (when-not (.exists f)
      (fail! reasons/reason-vault-not-found "vault not found"))
    (let [content (slurp f)]
      (try
        (json/read-str content)
        (catch Exception _
          (fail! reasons/reason-invalid-vault-file "invalid vault file"))))))

(defn init-vault!
  [path passphrase]
  (when (str/blank? (str passphrase))
    (fail! reasons/reason-missing-passphrase "no passphrase provided"))
  (let [f (io/file path)]
    (when (.exists f)
      (fail! reasons/reason-vault-exists (format "vault already exists: %s" path))))
  (let [encoded (encode-vault (empty-vault-data) passphrase {"iterations" default-iterations
                                                              "key_len" default-key-len-bytes})]
    (write-file-atomic! path (json/write-str encoded))
    path))

(defn open-vault
  [path passphrase]
  (let [outer (load-vault-file path)
        {:keys [meta data]} (decode-vault outer passphrase)]
    {:path path
     :meta meta
     :data data}))

(defn vault-info
  [path]
  (let [outer (load-vault-file path)
        {:keys [kdf]} (validate-vault-outer outer)]
    {"format_version" (get outer "format_version")
     "kdf" {"name" (get kdf "name")
             "iterations" (get kdf "iterations")
             "key_len" (get kdf "key_len")}}))

(defn- save-vault!
  [{:keys [path meta]} passphrase data]
  (let [kdf (or (get meta "kdf") {"iterations" default-iterations
                                   "key_len" default-key-len-bytes})
        encoded (encode-vault data passphrase kdf)]
    (write-file-atomic! path (json/write-str encoded))
    path))

(defn list-secret-names
  [path passphrase]
  (let [{:keys [data]} (open-vault path passphrase)
        secrets (get data "secrets")]
    (->> (keys secrets) sort vec)))

(defn get-secret
  [path passphrase name]
  (let [name (some-> name str/trim)]
    (when (str/blank? name)
      (fail! reasons/reason-empty-secret-name "empty secret name"))
    (let [{:keys [data]} (open-vault path passphrase)
          sec (get-in data ["secrets" name])]
      (when-not (map? sec)
        (fail! reasons/reason-secret-not-found "secret not found"))
      {:name name
       :type (get sec "type")
       :created_at (get sec "created_at")
       :updated_at (get sec "updated_at")
       :value (String. (b64-decode (or (get sec "value_b64") "")) "UTF-8")})))

(defn set-secret!
  [path passphrase name value]
  (let [name (some-> name str/trim)]
    (when (str/blank? name)
      (fail! reasons/reason-empty-secret-name "empty secret name"))
    (when (nil? value)
      (fail! reasons/reason-missing-secret-value "no secret value provided"))
    (let [value (str value)]
      (when (str/blank? value)
        (fail! reasons/reason-empty-secret-value "empty secret value"))
      (let [{:keys [data] :as opened} (open-vault path passphrase)
            now (now-iso)
            old (get-in data ["secrets" name])
            created-at (or (get old "created_at") now)
            updated (assoc old
                           "name" name
                           "created_at" created-at
                           "updated_at" now
                           "value_b64" (b64-encode (.getBytes value "UTF-8")))
            data' (assoc-in data ["secrets" name] updated)]
        (save-vault! opened passphrase data')
        {:name name
         :updated_at now}))))

(defn delete-secret!
  [path passphrase name]
  (let [name (some-> name str/trim)]
    (when (str/blank? name)
      (fail! reasons/reason-empty-secret-name "empty secret name"))
    (let [{:keys [data] :as opened} (open-vault path passphrase)]
      (when-not (contains? (get data "secrets") name)
        (fail! reasons/reason-secret-not-found "secret not found"))
      (let [data' (update data "secrets" dissoc name)]
        (save-vault! opened passphrase data')
        {:name name}))))

(defn rename-secret!
  [path passphrase old-name new-name]
  (let [old-name (some-> old-name str/trim)
        new-name (some-> new-name str/trim)]
    (when (or (str/blank? old-name) (str/blank? new-name))
      (fail! reasons/reason-empty-secret-name "empty secret name"))
    (when (= old-name new-name)
      (fail! reasons/reason-same-secret-name "source and destination names must differ"))
    (let [{:keys [data] :as opened} (open-vault path passphrase)
          secrets (get data "secrets")
          old (get secrets old-name)]
      (when-not (map? old)
        (fail! reasons/reason-secret-not-found "secret not found"))
      (when (contains? secrets new-name)
        (fail! reasons/reason-secret-exists "secret already exists"))
      (let [now (now-iso)
            moved (-> old
                      (assoc "name" new-name)
                      (assoc "updated_at" now))
            data' (-> data
                      (update "secrets" dissoc old-name)
                      (assoc-in ["secrets" new-name] moved))]
        (save-vault! opened passphrase data')
        {:from old-name
         :to new-name
         :updated_at now}))))

(defn rekey-vault!
  [path old-passphrase new-passphrase]
  (when (str/blank? (str new-passphrase))
    (fail! reasons/reason-missing-passphrase "no passphrase provided"))
  (let [{:keys [data]} (open-vault path old-passphrase)
        encoded (encode-vault data new-passphrase {"iterations" default-iterations
                                                   "key_len" default-key-len-bytes})]
    (write-file-atomic! path (json/write-str encoded))
    path))

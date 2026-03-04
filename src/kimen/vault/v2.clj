(ns kimen.vault.v2
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [kimen.json :as json]
   [kimen.reason-codes :as reasons])
  (:import
   [java.nio.file Files Path Paths StandardCopyOption]
   [java.nio.file.attribute PosixFilePermission]
   [java.security SecureRandom]
   [java.time Instant]
   [java.util Arrays Base64]
   [javax.crypto Cipher SecretKeyFactory]
   [javax.crypto.spec GCMParameterSpec PBEKeySpec SecretKeySpec]))

(def format-version "kimen-v2")
(def kdf-name "pbkdf2-hmac-sha256")
(def cipher-name "aes-gcm")
(def aad-vault "kimen-v2|vault")
(def aad-wrap-prefix "kimen-v2|wrap")
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
  [^bytes key ^bytes plaintext ^bytes nonce aad]
  (let [cipher (Cipher/getInstance "AES/GCM/NoPadding")
        key-spec (SecretKeySpec. key "AES")
        gcm (GCMParameterSpec. 128 nonce)]
    (.init cipher Cipher/ENCRYPT_MODE key-spec gcm)
    (.updateAAD cipher (.getBytes (str aad) "UTF-8"))
    (.doFinal cipher plaintext)))

(defn- auth-failure?
  [^Exception e]
  (let [class-name (.getName (class e))
        msg (some-> (.getMessage e) str/lower-case)]
    (or (str/includes? class-name "AEADBadTagException")
        (and msg (or (str/includes? msg "tag mismatch")
                     (str/includes? msg "mac check"))))))

(defn- decrypt-bytes
  [^bytes key ^bytes ciphertext ^bytes nonce aad wrong-passphrase? invalid-message]
  (try
    (let [cipher (Cipher/getInstance "AES/GCM/NoPadding")
          key-spec (SecretKeySpec. key "AES")
          gcm (GCMParameterSpec. 128 nonce)]
      (.init cipher Cipher/DECRYPT_MODE key-spec gcm)
      (.updateAAD cipher (.getBytes (str aad) "UTF-8"))
      (.doFinal cipher ciphertext))
    (catch Exception e
      (if (and wrong-passphrase? (auth-failure? e))
        (fail! reasons/reason-wrong-passphrase "wrong passphrase")
        (fail! reasons/reason-invalid-vault-file invalid-message)))))

(defn- concat-bytes
  [^bytes a ^bytes b]
  (let [out (byte-array (+ (alength a) (alength b)))]
    (System/arraycopy a 0 out 0 (alength a))
    (System/arraycopy b 0 out (alength a) (alength b))
    out))

(defn- burn-bytes!
  [^bytes b]
  (when (some? b)
    (Arrays/fill b (byte 0))))

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
    (when (and (contains? kdf "wrapped_dek_b64")
               (not (string? (get kdf "wrapped_dek_b64"))))
      (fail! reasons/reason-invalid-vault-file "invalid wrapped key metadata"))
    {:kdf kdf
     :enc enc
     :ciphertext-b64 ciphertext-b64}))

(defn- kdf-aad
  [kdf]
  (format "%s|%s|%s|%d|%d"
          format-version
          (get kdf "name")
          (get kdf "salt_b64")
          (long (or (get kdf "iterations") 0))
          (long (or (get kdf "key_len") 0))))

(defn- wrap-dek
  [^bytes kek ^bytes dek kdf]
  (let [nonce (random-bytes default-nonce-len)
        ciphertext (encrypt-bytes kek dek nonce (str aad-wrap-prefix "|" (kdf-aad kdf)))]
    (concat-bytes nonce ciphertext)))

(defn- unwrap-dek
  [^bytes kek wrapped-b64 kdf]
  (let [wrapped (b64-decode wrapped-b64)]
    (when (<= (alength wrapped) default-nonce-len)
      (fail! reasons/reason-invalid-vault-file "invalid wrapped key payload"))
    (let [nonce (byte-array default-nonce-len)
          ciphertext (byte-array (- (alength wrapped) default-nonce-len))]
      (System/arraycopy wrapped 0 nonce 0 default-nonce-len)
      (System/arraycopy wrapped default-nonce-len ciphertext 0 (alength ciphertext))
      (let [dek (decrypt-bytes kek ciphertext nonce (str aad-wrap-prefix "|" (kdf-aad kdf)) true "invalid wrapped key payload")]
        (when-not (= default-key-len-bytes (alength dek))
          (fail! reasons/reason-invalid-vault-file "invalid wrapped key length"))
        dek))))

(defn- decode-vault
  [m passphrase]
  (let [{:keys [kdf enc ciphertext-b64]} (validate-vault-outer m)
        kdf-name* (get kdf "name")
        iterations (long (or (get kdf "iterations") 0))
        key-len (long (or (get kdf "key_len") 0))
        salt-b64 (or (get kdf "salt_b64") "")
        salt (b64-decode salt-b64)
        wrapped-dek-b64 (get kdf "wrapped_dek_b64")
        cipher* (get enc "cipher")
        nonce (b64-decode (or (get enc "nonce_b64") ""))
        kdf' {"name" kdf-name*
              "iterations" iterations
              "key_len" key-len
              "salt_b64" salt-b64}]
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
    (let [kek (derive-key passphrase salt iterations key-len)]
      (try
        (let [data-key (if (some? wrapped-dek-b64)
                         (do
                           (when (str/blank? wrapped-dek-b64)
                             (fail! reasons/reason-invalid-vault-file "invalid wrapped key metadata"))
                           (unwrap-dek kek wrapped-dek-b64 kdf'))
                         kek)]
          (try
            (let [plaintext (decrypt-bytes data-key (b64-decode ciphertext-b64) nonce aad-vault true "invalid vault ciphertext")]
              (try
                (let [decoded (json/read-str (String. plaintext "UTF-8"))
                      secrets (get decoded "secrets")]
                  (when-not (map? decoded)
                    (fail! reasons/reason-invalid-vault-file "invalid vault payload"))
                  {:meta {"kdf" (cond-> kdf'
                                  (some? wrapped-dek-b64) (assoc "wrapped_dek_b64" wrapped-dek-b64))
                          "enc" {"cipher" cipher-name}
                          "format_version" format-version}
                   :data {"secrets" (if (map? secrets) secrets {})}})
                (finally
                  (burn-bytes! plaintext))))
            (finally
              (when (not (identical? data-key kek))
                (burn-bytes! data-key)))))
        (finally
          (burn-bytes! kek))))))

(defn- encode-vault
  [data passphrase kdf]
  (let [salt (if-let [v (get kdf "salt_b64")]
               (b64-decode v)
               (random-bytes default-salt-len))
        iterations (long (or (get kdf "iterations") default-iterations))
        key-len (long (or (get kdf "key_len") default-key-len-bytes))
        salt-b64 (b64-encode salt)
        kdf' {"name" kdf-name
              "iterations" iterations
              "key_len" key-len
              "salt_b64" salt-b64}
        kek (derive-key passphrase salt iterations key-len)
        dek (random-bytes default-key-len-bytes)
        nonce (random-bytes default-nonce-len)
        plaintext (.getBytes (json/write-str {"secrets" (or (get data "secrets") {})}) "UTF-8")]
    (try
      (let [wrapped-dek (wrap-dek kek dek kdf')
            ciphertext (encrypt-bytes dek plaintext nonce aad-vault)]
        {"format_version" format-version
         "kdf" (assoc kdf' "wrapped_dek_b64" (b64-encode wrapped-dek))
         "enc" {"cipher" cipher-name
                "nonce_b64" (b64-encode nonce)
                "aad" aad-vault}
         "ciphertext_b64" (b64-encode ciphertext)})
      (finally
        (burn-bytes! plaintext)
        (burn-bytes! dek)
        (burn-bytes! kek)))))

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
  (let [encoded (encode-vault (empty-vault-data)
                              passphrase
                              {"iterations" default-iterations
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
  (let [kdf (or (get meta "kdf")
                {"iterations" default-iterations
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
        encoded (encode-vault data
                              new-passphrase
                              {"iterations" default-iterations
                               "key_len" default-key-len-bytes})]
    (write-file-atomic! path (json/write-str encoded))
    path))

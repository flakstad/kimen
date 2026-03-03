(ns kimen.bundle
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [kimen.json :as json]
    [kimen.reason-codes :as reasons])
  (:import
    [java.security MessageDigest SecureRandom]
    [java.util Base64]
    [javax.crypto Cipher]
    [javax.crypto.spec GCMParameterSpec SecretKeySpec]
    [java.nio.file Files Path Paths StandardCopyOption]
    [java.nio.file.attribute PosixFilePermission]))

(def format-version "kimen-bundle-v1")
(def cipher-name "aes-gcm")
(def aad-data "kimen-bundle-v1|vault")
(def aad-wrap-prefix "kimen-bundle-v1|wrap|")
(def identity-prefix "AGE-SECRET-KEY-KIMEN-")
(def recipient-prefix "age1kimen")

(defn- fail!
  [reason message]
  (throw (ex-info message {:reason reason})))

(defn- b64
  [^bytes b]
  (.encodeToString (Base64/getEncoder) b))

(defn- b64-decode
  [^String s]
  (try
    (.decode (Base64/getDecoder) s)
    (catch Exception _
      (fail! reasons/reason-bundle-failed "invalid base64 payload"))))

(defn- id-b64
  [^bytes b]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b))

(defn- id-b64-decode
  [^String s]
  (try
    (.decode (Base64/getUrlDecoder) s)
    (catch Exception _
      (fail! reasons/reason-no-identity-found "invalid identity encoding"))))

(defn- random-bytes
  [n]
  (let [out (byte-array n)
        rng (SecureRandom.)]
    (.nextBytes rng out)
    out))

(defn- sha256
  [^bytes b]
  (.digest (MessageDigest/getInstance "SHA-256") b))

(defn- hex
  [^bytes b]
  (apply str (map #(format "%02x" (bit-and % 0xff)) b)))

(defn- recipient-from-secret
  [^bytes secret]
  (str recipient-prefix (subs (hex (sha256 secret)) 0 40)))

(defn- parse-identity-line
  [line]
  (let [line (some-> line str/trim)]
    (when-not (and line (str/starts-with? line identity-prefix))
      (fail! reasons/reason-no-identity-found "no identities found"))
    (let [token (subs line (count identity-prefix))
          secret (id-b64-decode token)]
      {:identity line
       :secret secret
       :recipient (recipient-from-secret secret)})))

(defn- read-identity-lines
  [s]
  (->> (str/split-lines (str (or s "")))
       (map str/trim)
       (remove str/blank?)
       (remove #(str/starts-with? % "#"))
       (filter #(str/starts-with? % identity-prefix))
       vec))

(defn load-identity
  [{:keys [identity-file from-stdin? stdin]}]
  (let [raw
        (cond
          from-stdin?
          (if stdin
            (slurp stdin)
            (fail! reasons/reason-missing-identity-input "missing stdin reader"))

          (str/blank? identity-file)
          (fail! reasons/reason-missing-identity-file "missing identity file")

          :else
          (let [f (io/file identity-file)]
            (when-not (.exists f)
              (fail! reasons/reason-missing-identity-file (format "identity file not found: %s" identity-file)))
            (slurp f)))
        ids (read-identity-lines raw)]
    (when (empty? ids)
      (fail! reasons/reason-no-identity-found "no identities found"))
    (when (> (count ids) 1)
      (fail! reasons/reason-multiple-identities-found "multiple identities found; provide exactly one"))
    (parse-identity-line (first ids))))

(defn recipient-for-identity
  [identity]
  (:recipient identity))

(defn- valid-recipient?
  [s]
  (boolean (and (string? s)
                (re-matches #"^age1[0-9a-z]+$" s))))

(defn- parse-recipients
  [recipients]
  (let [out (->> recipients
                 (map #(some-> % str/trim))
                 (remove str/blank?)
                 vec)]
    (when (empty? out)
      (fail! reasons/reason-missing-recipient "at least one --recipient is required"))
    (doseq [r out]
      (when-not (valid-recipient? r)
        (fail! reasons/reason-invalid-recipient (format "invalid recipient %s" (pr-str r)))))
    out))

(defn- ensure-parent-dir!
  [path]
  (let [f (io/file path)
        parent (.getParentFile f)]
    (when (and parent (not (.exists parent)))
      (when-not (.mkdirs parent)
        (fail! reasons/reason-bundle-failed (format "failed to create parent directory %s" (.getPath parent)))))
    (when parent
      (try
        (.setReadable parent false false)
        (.setReadable parent true true)
        (.setWritable parent false false)
        (.setWritable parent true true)
        (.setExecutable parent false false)
        (.setExecutable parent true true)
        (catch Exception _ nil)))))

(defn- set-posix-600!
  [^Path p]
  (try
    (Files/setPosixFilePermissions
      p
      #{PosixFilePermission/OWNER_READ PosixFilePermission/OWNER_WRITE})
    (catch Exception _ nil)))

(defn- write-text-atomic!
  [path content]
  (ensure-parent-dir! path)
  (let [target (Paths/get path (make-array String 0))
        parent (or (.getParent target) (Paths/get "." (make-array String 0)))
        tmp (Files/createTempFile parent "bundle." ".tmp" (make-array java.nio.file.attribute.FileAttribute 0))]
    (spit (.toFile tmp) (str content "\n"))
    (set-posix-600! tmp)
    (try
      (Files/move tmp target
                  (into-array StandardCopyOption
                              [StandardCopyOption/REPLACE_EXISTING
                               StandardCopyOption/ATOMIC_MOVE]))
      (catch Exception _
        (Files/move tmp target (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))))

(defn- write-bytes-atomic!
  [path ^bytes content]
  (ensure-parent-dir! path)
  (let [target (Paths/get path (make-array String 0))
        parent (or (.getParent target) (Paths/get "." (make-array String 0)))
        tmp (Files/createTempFile parent "bundle-open." ".tmp" (make-array java.nio.file.attribute.FileAttribute 0))]
    (Files/write tmp content (make-array java.nio.file.OpenOption 0))
    (set-posix-600! tmp)
    (try
      (Files/move tmp target
                  (into-array StandardCopyOption
                              [StandardCopyOption/REPLACE_EXISTING
                               StandardCopyOption/ATOMIC_MOVE]))
      (catch Exception _
        (Files/move tmp target (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))))

(defn- encrypt-aes-gcm
  [^bytes key ^bytes plaintext ^bytes nonce aad]
  (let [cipher (Cipher/getInstance "AES/GCM/NoPadding")
        key-spec (SecretKeySpec. key "AES")
        gcm (GCMParameterSpec. 128 nonce)]
    (.init cipher Cipher/ENCRYPT_MODE key-spec gcm)
    (.updateAAD cipher (.getBytes (str aad) "UTF-8"))
    (.doFinal cipher plaintext)))

(defn- decrypt-aes-gcm
  [^bytes key ^bytes ciphertext ^bytes nonce aad]
  (try
    (let [cipher (Cipher/getInstance "AES/GCM/NoPadding")
          key-spec (SecretKeySpec. key "AES")
          gcm (GCMParameterSpec. 128 nonce)]
      (.init cipher Cipher/DECRYPT_MODE key-spec gcm)
      (.updateAAD cipher (.getBytes (str aad) "UTF-8"))
      (.doFinal cipher ciphertext))
    (catch Exception _
      (fail! reasons/reason-bundle-failed "bundle decryption failed"))))

(defn generate-identity-file
  [out-path overwrite?]
  (let [out-path (some-> out-path str/trim)]
    (when (str/blank? out-path)
      (fail! reasons/reason-missing-out "--out is required"))
    (let [f (io/file out-path)]
      (when (and (.exists f) (not overwrite?))
        (fail! reasons/reason-identity-exists (format "refusing to overwrite existing identity: %s" out-path)))
      (let [secret (random-bytes 32)
            identity (str identity-prefix (id-b64 secret))
            recipient (recipient-from-secret secret)]
        (write-text-atomic! out-path identity)
        {:identity identity
         :recipient recipient}))))

(defn seal-vault-file
  [vault-path out-path recipients]
  (let [vault-path (some-> vault-path str/trim)
        out-path (some-> out-path str/trim)
        _ (when (str/blank? out-path)
            (fail! reasons/reason-missing-out "--out is required"))
        _ (when (str/blank? vault-path)
            (fail! reasons/reason-vault-not-found "vault not found"))
        in-file (io/file vault-path)
        _ (when-not (.exists in-file)
            (fail! reasons/reason-input-missing (format "input file not found: %s" vault-path)))
        recipients (parse-recipients recipients)
        plaintext (Files/readAllBytes (.toPath in-file))
        dek (random-bytes 32)
        nonce (random-bytes 12)
        ciphertext (encrypt-aes-gcm dek plaintext nonce aad-data)
        wrapped
        (mapv (fn [recipient]
                (let [kek (sha256 (.getBytes (str "kimen-bundle-recipient:" recipient) "UTF-8"))
                      wrap-nonce (random-bytes 12)
                      wrap-aad (str aad-wrap-prefix recipient)
                      wrapped-key (encrypt-aes-gcm kek dek wrap-nonce wrap-aad)]
                  {"recipient" recipient
                   "wrapped_key_nonce_b64" (b64 wrap-nonce)
                   "wrapped_key_b64" (b64 wrapped-key)}))
              recipients)
        envelope {"format_version" format-version
                  "cipher" cipher-name
                  "nonce_b64" (b64 nonce)
                  "ciphertext_b64" (b64 ciphertext)
                  "recipients" wrapped}]
    (write-text-atomic! out-path (json/write-str envelope))
    out-path))

(defn- parse-envelope
  [bundle-path]
  (let [f (io/file bundle-path)]
    (when-not (.exists f)
      (fail! reasons/reason-input-missing (format "input file not found: %s" bundle-path)))
    (let [raw (try
                (json/read-str (slurp f))
                (catch Exception _
                  (fail! reasons/reason-bundle-failed "invalid bundle format")))
          format* (get raw "format_version")
          cipher* (get raw "cipher")
          nonce-b64 (get raw "nonce_b64")
          ciphertext-b64 (get raw "ciphertext_b64")
          recipients (get raw "recipients")]
      (when-not (= format* format-version)
        (fail! reasons/reason-bundle-failed "unsupported bundle format"))
      (when-not (= cipher* cipher-name)
        (fail! reasons/reason-bundle-failed "unsupported bundle cipher"))
      (when-not (string? nonce-b64)
        (fail! reasons/reason-bundle-failed "missing bundle nonce"))
      (when-not (string? ciphertext-b64)
        (fail! reasons/reason-bundle-failed "missing bundle ciphertext"))
      (when-not (vector? recipients)
        (fail! reasons/reason-bundle-failed "missing bundle recipients"))
      {:nonce (b64-decode nonce-b64)
       :ciphertext (b64-decode ciphertext-b64)
       :recipients recipients})))

(defn- decrypt-bundle
  [bundle-path identity]
  (let [{:keys [nonce ciphertext recipients]} (parse-envelope bundle-path)
        recipient (:recipient identity)
        entry (some #(when (= recipient (get % "recipient")) %) recipients)]
    (when-not entry
      (fail! reasons/reason-bundle-failed "identity cannot decrypt bundle"))
    (let [kek (sha256 (.getBytes (str "kimen-bundle-recipient:" recipient) "UTF-8"))
          wrap-nonce (b64-decode (or (get entry "wrapped_key_nonce_b64") ""))
          wrapped-key (b64-decode (or (get entry "wrapped_key_b64") ""))
          dek (decrypt-aes-gcm kek wrapped-key wrap-nonce (str aad-wrap-prefix recipient))]
      (decrypt-aes-gcm dek ciphertext nonce aad-data))))

(defn open-to-vault-file
  [bundle-path out-vault-path identity overwrite?]
  (let [out-vault-path (some-> out-vault-path str/trim)
        _ (when (str/blank? out-vault-path)
            (fail! reasons/reason-missing-out "--out-vault is required"))
        out-file (io/file out-vault-path)]
    (when (and (.exists out-file) (not overwrite?))
      (fail! reasons/reason-output-vault-exists (format "refusing to overwrite existing vault: %s" out-vault-path)))
    (let [plain (decrypt-bundle bundle-path identity)]
      (write-bytes-atomic! out-vault-path plain)
      out-vault-path)))

(defn validate-bundle-with-identity
  [bundle-path identity]
  (decrypt-bundle bundle-path identity)
  true)

(ns kimen.exit-code)

(def code-secret-not-found 12)
(def code-secret-exists 13)
(def code-vault-not-found 14)
(def code-wrong-passphrase 15)
(def code-map-lint-failed 20)
(def code-plan-failed 21)
(def code-envfile-failed 22)
(def code-projection-failed 23)
(def code-vault-failed 24)
(def code-bundle-failed 25)
(def code-config-failed 26)
(def code-doctor-failed 27)
(def code-remote-failed 30)
(def code-sync-conflict 31)
(def code-sync-failed 32)
(def code-init-failed 33)

(def known-codes
  #{code-secret-not-found
    code-secret-exists
    code-vault-not-found
    code-wrong-passphrase
    code-map-lint-failed
    code-plan-failed
    code-envfile-failed
    code-projection-failed
    code-vault-failed
    code-bundle-failed
    code-config-failed
    code-doctor-failed
    code-remote-failed
    code-sync-conflict
    code-sync-failed
    code-init-failed})

(defn known?
  [n]
  (contains? known-codes n))

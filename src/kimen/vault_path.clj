(ns kimen.vault-path
  (:require
    [clojure.string :as str]
    [kimen.config :as config]))

(set! *warn-on-reflection* true)

(def env-vault-path "KIMEN_VAULT")

(defn default-vault-path
  []
  (str (config/default-kimen-dir) "/vault.db"))

(defn resolve-vault-path
  [ctx explicit-vault-path]
  (or (some-> explicit-vault-path str/trim not-empty)
      (some-> (System/getenv env-vault-path) str/trim not-empty)
      (let [configured (config/config-vault-show (:config-path ctx))]
        (some-> configured str/trim not-empty))
      (default-vault-path)))

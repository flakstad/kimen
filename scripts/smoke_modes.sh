#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "[smoke-modes] babashka launcher"
bb run -- version --json >/dev/null

echo "[smoke-modes] jvm main alias"
clojure -M:run -- version --json >/dev/null

echo "[smoke-modes] jar build + java -jar"
bb build-jar >/dev/null
java -jar target/kimen.jar version --json >/dev/null

echo "[smoke-modes] native build + binary smoke"
bb build-native >/dev/null
./target/kimen version --json >/dev/null

echo "[smoke-modes] library embedding via kimen.api"
clojure -Sdeps '{:deps {kimen/kimen {:local/root "."}}}' -M -e \
  '(require (quote [kimen.api :as api]))
   (let [res (api/run ["version" "--json"])]
     (when-not (zero? (:exit-code res))
       (throw (ex-info "api smoke failed" res))))'

echo "[smoke-modes] PASS"

(ns kimen.cli.usage
  (:require
   [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def usage
  (str/join
   "\n"
   ["kimen"
    "Secret management, projection, and sync."
    ""
    "Usage:"
    "  kimen <command> [<subcommand>] [options]"
    "  kimen help <command>"
    ""
    "Commands:"
    "  version   Build and version metadata"
    "  config    Show or edit local config"
    "  vault     Initialize, inspect, and rekey vault files"
    "  secret    Set, list, read, remove, or rename secrets"
    "  remote    Manage sync remote definitions"
    "  sync      Sync workflows (push/pull/status/conflicts/etc.)"
    "  bundle    age key and bundle operations"
    "  map       Mapping utilities (currently: lint)"
    "  plan      Show projection plan/diff"
    "  run       Run a command with projected env/files/stdin"
    "  render    Render projected files"
    "  envfile   Generate envfile output"
    "  doctor    Validate config/vault/map/bundle/remote health"
    "  init      Generate CI helper scripts"
    "  project   Aliases for run/render/plan"
    "  completion Generate shell completion scripts"
    ""
    "Use `kimen <command> --help` for subcommand usage."
    ""]))

(def config-usage
  (str/join
   "\n"
   ["kimen config"
    ""
    "Usage:"
    "  kimen config path [--json|--edn]"
    "  kimen config show [--pretty=true|false]"
    "  kimen config vault set <vault-path> [--json|--edn]"
    "  kimen config vault show [--json|--edn]"
    "  kimen config vault clear [--json|--edn]"
    "  kimen config unlock set <prompt|env|stdin|exec> [-- <command> [args...]] [--json|--edn]"
    "  kimen config unlock show [--json|--edn]"
    "  kimen config unlock clear [--json|--edn]"
    ""]))

(def vault-usage
  (str/join
   "\n"
   ["kimen vault"
    ""
    "Usage:"
    "  kimen vault init [--vault <path>] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json|--edn]"
    "  kimen vault info [--vault <path>] [--json|--edn]"
    "  kimen vault path [--vault <path>] [--json|--edn]"
    "  kimen vault rekey [--vault <path>] [--passphrase-stdin|--passphrase-cmd <cmd>|--old-passphrase-file <path>] [--new-passphrase-stdin|--new-passphrase-cmd <cmd>|--new-passphrase-file <path>|--new-passphrase-env <VAR>] [--dry-run] [--no-backup|--backup-dir <path>] [--json|--edn]"
    ""]))

(def secret-usage
  (str/join
   "\n"
   ["kimen secret"
    ""
    "Usage:"
    "  kimen secret set <name> [--stdin|--value <text>] [--vault <path>] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json|--edn]"
    "  kimen secret list [--vault <path>] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json|--edn]"
    "  kimen secret get <name> --unsafe-stdout [--vault <path>] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json|--edn]"
    "  kimen secret rm <name> [--vault <path>] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json|--edn]"
    "  kimen secret mv <old-name> <new-name> [--vault <path>] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json|--edn]"
    ""]))

(def secret-set-usage-line
  "Usage: kimen secret set <name> [--stdin|--value <text>] [--vault <path>] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json|--edn]")

(def secret-get-usage-line
  "Usage: kimen secret get <name> --unsafe-stdout [--vault <path>] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json|--edn]")

(def secret-rm-usage-line
  "Usage: kimen secret rm <name> [--vault <path>] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json|--edn]")

(def secret-mv-usage-line
  "Usage: kimen secret mv <old-name> <new-name> [--vault <path>] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json|--edn]")

(def remote-usage
  (str/join
   "\n"
   ["kimen remote"
    ""
    "Usage:"
    "  kimen remote add <name> --path <path> [--type fs|git] [--recipient <age1...>] [--identity <path>] [--branch <name>] [--bundle-path <path>] [--derive-recipient|--no-derive-recipient] [--json|--edn]"
    "  kimen remote get <name> [--json|--edn]"
    "  kimen remote set <name> [--type fs|git] [--path <path>] [--recipient <age1...>] [--identity <path>] [--branch <name>] [--bundle-path <path>] [--derive-recipient|--no-derive-recipient] [--json|--edn]"
    "  kimen remote list [--json|--edn]"
    "  kimen remote rm <name> [--json|--edn]"
    ""]))

(def sync-usage
  (str/join
   "\n"
   ["kimen sync"
    ""
    "Usage:"
    "  kimen sync [--remote <name>] [--dry-run] [--check] [--no-doctor] [--strict] [--terse] [--stale-threshold <dur>] [--profile <name>] [--bundle-in <path>] [--identity <path>] [--allow-missing-vault] [--force] [--reconcile] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json|--edn]"
    "  kimen sync init [name] [--remote <name>] [--type fs|git] [--path <path>] [--recipient <age1...>] [--identity <path>] [--branch <name>] [--bundle-path <path>] [--update] [--no-check] [--json|--edn]"
    "  kimen sync preflight [--remote <name>] [--profile <name>] [--bundle-in <path>] [--identity <path>] [--stale-threshold <dur>] [--strict] [--allow-missing-vault] [--only <check>] [--skip <check>] [--json|--edn]"
    "  kimen sync status [--remote <name>] [--stale-threshold <dur>] [--strict] [--terse] [--json|--edn]"
    "  kimen sync conflicts [--remote <name>] [--stale-threshold <dur>] [--strict] [--terse] [--json|--edn]"
    "  kimen sync changes [--remote <name>] [--terse] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json|--edn]"
    "  kimen sync push [--remote <name>] [--dry-run] [--force] [--lock-wait <dur>] [--break-stale-lock-after <dur>] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json|--edn]"
    "  kimen sync pull [--remote <name>] [--dry-run] [--reconcile] [--no-backup] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json|--edn]"
    "  kimen sync resolve [--remote <name>] --take local|remote [--key <name>] [--key <name> ...] [--passphrase-stdin|--passphrase-cmd <cmd>] [--json|--edn]"
    "  kimen sync reset-baseline [--remote <name>] (--clear|--to-remote|--rev <sha256>) --yes [--passphrase-stdin|--passphrase-cmd <cmd>] [--json|--edn]"
    "  kimen sync unlock [--remote <name>] [--if-older-than <dur>] --yes [--json|--edn]"
    "  kimen sync restore --backup <path> [--no-backup] [--json|--edn]"
    ""]))

(def bundle-usage
  (str/join
   "\n"
   ["kimen bundle"
    ""
    "Usage:"
    "  kimen bundle keygen --out <path> [--overwrite] [--print-recipient] [--json|--edn]"
    "  kimen bundle recipient (--identity <path>|--identity-stdin) [--json|--edn]"
    "  kimen bundle seal [--vault <path>] --out <path> --recipient <age1...> [--recipient <age1...> ...] [--json|--edn]"
    "  kimen bundle open --in <path> [--out-vault <path>] (--identity <path>|--identity-stdin) [--overwrite] [--json|--edn]"
    ""]))

(def map-usage
  (str/join
   "\n"
   ["kimen map"
    ""
    "Usage:"
    "  kimen map lint [--map <path>|--profile <name>] [--mode all|run|render|envfile] [--strict] [--json|--edn]"
    ""]))

(def init-usage
  (str/join
   "\n"
   ["kimen init"
    ""
    "Usage:"
    "  kimen init ci-pr-safety [--out <path>] [--force] [--profile <name>] [--command <cmd>] [--json|--edn]"
    "  kimen init ci-deploy [--out <path>] [--force] [--profile <name>] [--deploy-command <cmd>] [--json|--edn]"
    "  kimen init ci-sync-gate [--out <path>] [--force] [--remote-name <name>] [--remote-type git|fs] [--remote-path <path>] [--remote-branch <name>] [--remote-bundle-path <path>] [--local-bundle <path>] [--profile <name>] [--stale-threshold <dur>] [--json|--edn]"
    ""]))

(def run-usage
  (str/join
   "\n"
   ["kimen run"
    ""
    "Usage:"
    "  kimen run [--map <path>|--profile <name>] [--env VAR=<value>] [--file relpath=<value>] [--envpath VAR=relpath] [--stdin <value>] [--files-dir <path>] [--json|--edn] [--dry-run] [-- <command> [args...]]"
    ""]))

(def render-usage
  (str/join
   "\n"
   ["kimen render"
    ""
    "Usage:"
    "  kimen render [--map <path>|--profile <name>] [--file relpath=<value>] (--dir <path>|--systemd-service <name>) [--runtime-dir <path>] [--print-systemd-hints] [--json|--edn]"
    ""]))

(def envfile-usage
  (str/join
   "\n"
   ["kimen envfile"
    ""
    "Usage:"
    "  kimen envfile [--map <path>|--profile <name>] [--env VAR=<value>] [--file relpath=<value>] [--envpath VAR=relpath] --out <path> [--files-dir <path>] [--json|--edn]"
    ""]))

(def plan-usage
  (str/join
   "\n"
   ["kimen plan"
    ""
    "Usage:"
    "  kimen plan [--map <path>|--profile <name>] [--env VAR=<value>] [--file relpath=<value>] [--envpath VAR=relpath] [--stdin <value>] [--against-map <path>|--against-profile <name>] [--mode run|render|envfile] [--json|--edn] [-- <command> [args...]]"
    ""]))

(def doctor-usage
  (str/join
   "\n"
   ["kimen doctor"
    ""
    "Usage:"
    "  kimen doctor [--map <path>|--profile <name>] [--bundle-in <path>] [--identity <path>] [--strict] [--allow-missing-vault] [--json|--edn]"
    ""]))

(def project-usage
  (str/join
   "\n"
   ["kimen project"
    ""
    "Usage:"
    "  kimen project run [--map <path>|--profile <name>] [--env VAR=<value>] [--file relpath=<value>] [--envpath VAR=relpath] [--stdin <value>] [--files-dir <path>] [--json|--edn] [--dry-run] [-- <command> [args...]]"
    "  kimen project render [--map <path>|--profile <name>] [--file relpath=<value>] (--dir <path>|--systemd-service <name>) [--runtime-dir <path>] [--print-systemd-hints] [--json|--edn]"
    "  kimen project plan [--map <path>|--profile <name>] [--env VAR=<value>] [--file relpath=<value>] [--envpath VAR=relpath] [--stdin <value>] [--against-map <path>|--against-profile <name>] [--mode run|render|envfile] [--json|--edn] [-- <command> [args...]]"
    ""]))

(defn help-topics
  [completion-usage]
  {"version" (str/join "\n" ["kimen version" "" "Usage:" "  kimen version [--json|--edn]" ""])
   "config" config-usage
   "vault" vault-usage
   "secret" secret-usage
   "remote" remote-usage
   "sync" sync-usage
   "bundle" bundle-usage
   "map" map-usage
   "plan" plan-usage
   "run" run-usage
   "render" render-usage
   "envfile" envfile-usage
   "doctor" doctor-usage
   "init" init-usage
   "project" project-usage
   "completion" completion-usage})

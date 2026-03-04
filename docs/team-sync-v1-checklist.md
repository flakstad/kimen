# Team Sync v1 Checklist

Goal: ship a reliable, automatable team sync surface for `fs` and `git` remotes without changing Kimen’s local-first invariant.

## 1) Contract Stability

- [x] Canonical contract documented in `docs/automation-contract.md`
- [x] Structured sync conflict errors (`31`) with `reason` + `recommended_action`
- [x] Structured lock/precondition errors (`32`) where available
- [x] `sync status`/`sync conflicts` JSON fields stabilized for automation

## 2) Operating Models and Runbooks

- [x] Single-writer model documented
- [x] Coordinated multi-writer model documented
- [x] Conflict, lock, and remote-loss runbooks documented
- [x] CI strict-gating workflow documented

Reference: `docs/team-sync-v1.md`

## 3) Safety and Preflight

- [x] `kimen doctor` remote readiness checks (`fs` + `git`)
- [x] `doctor` detects stale sync baseline entries
- [x] `doctor` reports remote sync-state drift vs baseline
- [x] `kimen sync pull --dry-run` implemented (no mutation)
- [x] `kimen sync push --dry-run` implemented (no mutation)
- [x] `kimen sync conflicts --strict` implemented for fail-fast gating
- [x] `kimen sync preflight --strict --json` implemented as one-command strict gate

## 4) End-to-End Confidence

- [x] fs sync e2e harness (`scripts/e2e-sync.sh`)
- [x] git sync e2e harness (`scripts/e2e-sync-git.sh`)
- [x] e2e harnesses cover dry-run paths
- [x] CI runs sync e2e checks
- [x] `make release-check` includes fs + git sync e2e

## Exit Criteria

- [x] `go test ./...` passes
- [x] `make go-sync-e2e-all` passes
- [x] Team Sync v1 docs published

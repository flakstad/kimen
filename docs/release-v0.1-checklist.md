# Release Checklist (CalVer)

This checklist is for cutting the first external milestone from branch `agent/agent/secret-rm-mv`.

## 1) Freeze and verify

- [ ] `make release-check` passes.
- [ ] `go test ./...` passes on the release commit.
- [ ] `go build -o dist/kimen ./cmd/kimen` succeeds.
- [ ] Smoke pass completed for:
  - `vault`, `secret`, `run`, `render`, `envfile`
  - `plan` / `project plan`
  - `bundle` and `doctor`
- [ ] CI templates reviewed:
  - `.github/workflows/kimen-pr-safety-template.yml`
  - `.github/workflows/kimen-deploy-template.yml`

## 2) Docs sanity

- [ ] `README.md` links are current.
- [ ] `docs/automation-contract.md` is the canonical machine contract.
- [ ] `docs/recommended-paths.md` reflects preferred usage.
- [ ] `docs/ci-workflow-templates.md` matches template content.

## 3) Tag + publish

- [ ] Merge branch into target release branch.
- [ ] Create annotated CalVer tag (example): `v2026.2.1`.
- [ ] Push tag and confirm `.github/workflows/release.yml` runs.
- [ ] Publish release notes with:
  - projection workflow summary
  - map/profile/plan/envfile status
  - automation contract and exit-code coverage
  - known limitations and next milestone scope

## 4) Feedback loop

- [ ] Share release with 3-5 early users.
- [ ] Ask for responses using `docs/feedback-template.md`.
- [ ] Track findings under labels:
  - `feedback-ci`
  - `feedback-devx`
  - `feedback-docs`
  - `feedback-reliability`

## 5) Exit criteria for next milestone

- [ ] At least 3 real workflows validated (dev, CI, systemd-like runtime).
- [ ] No critical automation contract mismatches reported.
- [ ] Prioritized top 5 follow-up issues from feedback.

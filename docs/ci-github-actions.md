# GitHub Actions (CI) pattern

Kimen is local-first, but CI still needs secrets. The recommended model is:

- CI is its own **identity**
- CI decrypts an encrypted **bundle** locally in the job
- Kimen performs **projections** locally (env/files/run)

The transport that stores the encrypted bundle is “no-trust”: it only holds ciphertext.

If you want ready-to-copy workflow files, see:

- `.github/workflows/kimen-pr-safety-template.yml`
- `.github/workflows/kimen-deploy-template.yml`
- `.github/workflows/kimen-sync-gate-template.yml`
- `docs/ci-workflow-templates.md`

## Bootstrap once: CI identity and bundle

1) Generate an age identity for CI (private key stays in GitHub Actions Secrets):

```bash
kimen bundle keygen --out ci.agekey
kimen bundle recipient --identity ci.agekey > ci.agepub
```

2) Seal the vault to the CI recipient:

```bash
kimen bundle seal --vault "$KIMEN_VAULT" --out vault.age --recipient "$(cat ci.agepub)"
```

Commit `vault.age` (ciphertext) to your repo, or upload it to a dumb blob store.

3) Store the CI private key (`ci.agekey`) in GitHub as an Actions secret, e.g. `KIMEN_AGE_IDENTITY`.

## Template A: PR safety gate (lint + plan, no secret values)

This validates map/profile intent in pull requests before deploy.

```yaml
name: kimen-pr-safety
on:
  pull_request:
    paths:
      - ".kimen/**"
      - "docs/**"
      - "internal/**"
      - "cmd/**"

jobs:
  safety:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-go@v5
        with:
          go-version-file: go.mod

      - run: go build -o kimen ./cmd/kimen

      - name: Lint profile map
        run: ./kimen map lint --profile linje-prod --json | tee kimen-map-lint.json

      - name: Plan projection intent
        run: ./kimen project plan --profile linje-prod --json -- ./bin/service --check | tee kimen-plan.json
```

Notes:

- `map lint` fails with exit code `20` on errors.
- `project plan` never outputs secret values; safe to archive as artifact.

## Template B: Deploy with bundle open + projections

This decrypts a ciphertext bundle in-job, then runs projection-based deployment.

```yaml
name: kimen-deploy
on:
  workflow_dispatch:

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-go@v5
        with:
          go-version-file: go.mod

      - run: go build -o kimen ./cmd/kimen

      - name: Open vault bundle
        env:
          KIMEN_AGE_IDENTITY: ${{ secrets.KIMEN_AGE_IDENTITY }}
          KIMEN_VAULT: ${{ runner.temp }}/kimen/vault.db
        run: |
          mkdir -p "$(dirname "$KIMEN_VAULT")"
          printf '%s\n' "$KIMEN_AGE_IDENTITY" \
            | ./kimen bundle open \
              --in vault.age \
              --identity-stdin \
              --out-vault "$KIMEN_VAULT" \
              --overwrite \
              --json | tee kimen-bundle-open.json

      - name: Validate map before deploy
        run: ./kimen map lint --profile linje-prod --json | tee kimen-map-lint.json

      - name: Materialize envfile for deploy tooling
        env:
          KIMEN_PASSPHRASE: ${{ secrets.KIMEN_PASSPHRASE }}
        run: |
          ./kimen envfile \
            --profile linje-prod \
            --out "${RUNNER_TEMP}/linje.env" \
            --json | tee kimen-envfile.json

      - name: Run deploy command with scoped projections
        env:
          KIMEN_PASSPHRASE: ${{ secrets.KIMEN_PASSPHRASE }}
        run: |
          ./kimen project run \
            --profile linje-prod \
            -- ./scripts/deploy.sh
```

## Template C: Team Sync strict gate (status/conflicts + dry-runs)

Use this when you want CI to fail fast if Team Sync is not ready.

Template file:

- `.github/workflows/kimen-sync-gate-template.yml`

Required GitHub secrets:

- `KIMEN_AGE_IDENTITY`
- `KIMEN_AGE_RECIPIENT`
- `KIMEN_PASSPHRASE`

Template gates include:

- `kimen doctor --strict --json`
- `kimen sync status --strict --json`
- `kimen sync conflicts --strict --json`
- `kimen sync pull --dry-run --json`
- `kimen sync push --dry-run --json`

Typical usage:

1. Set remote inputs (`remote_type`, `remote_path`, and git-specific branch/path when relevant).
2. Run the workflow via `workflow_dispatch`.
3. Inspect uploaded `kimen-*.json` artifacts for root-cause details.

## Minimal bundle-open step (reference)

```yaml
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-go@v5
        with:
          go-version-file: go.mod

      - run: go build -o kimen ./cmd/kimen

      - name: Open vault bundle
        env:
          KIMEN_AGE_IDENTITY: ${{ secrets.KIMEN_AGE_IDENTITY }}
          KIMEN_VAULT: ${{ runner.temp }}/kimen/vault.db
        run: |
          mkdir -p "$(dirname "$KIMEN_VAULT")"
          printf '%s\n' "$KIMEN_AGE_IDENTITY" | ./kimen bundle open --in vault.age --identity-stdin --out-vault "$KIMEN_VAULT" --overwrite

      - name: Use projections
        env:
          KIMEN_PASSPHRASE: ${{ secrets.KIMEN_PASSPHRASE }}
        run: |
          ./kimen run --env API_KEY=api_key -- your-build-step
```

Notes:

- This trusts GitHub Actions Secrets to hold the CI identity and/or vault passphrase.
- The bundle transport (repo/blob store) never needs plaintext access.
- Prefer `kimen project run` so secrets are realized only for the child process lifetime.
- For automation, prefer `--json` on `bundle`/`envfile`/`vault`/`config` commands and consume typed exit codes in scripts.

# CI Workflow Templates

This repo includes two starter workflow templates in `.github/workflows/`:

- `kimen-pr-safety-template.yml`
- `kimen-deploy-template.yml`

These are intentionally `workflow_dispatch` templates so they do not auto-run until you adapt them.

## Which template to use

Use `kimen-pr-safety-template.yml` when you want:

- fast checks for map/profile changes
- machine-readable artifacts from `map lint` and `project plan`
- no secret materialization

Use `kimen-deploy-template.yml` when you want:

- decrypting `vault.age` in-job via `bundle open`
- preflight validation with `kimen doctor --bundle-in ... --identity ... --strict`
- deploy steps run under `kimen project run`
- uploaded artifacts (`kimen-*.json`) for post-run inspection

## Required edits before enabling

1. Profile names:
   - Update `inputs.profile` defaults to your real profile names.
2. Deploy command:
   - Replace `./scripts/deploy.sh` with your actual deploy command.
3. Bundle location:
   - Ensure `vault.age` path matches your repo layout.
4. Secrets:
   - Add `KIMEN_AGE_IDENTITY` and `KIMEN_PASSPHRASE` in GitHub Actions secrets.

## Built-in checklist gates

Both templates now include a fail-fast checklist step before the main work:

- required profile map exists
- required bundle file exists (`vault.age` in deploy template)
- required GitHub secrets are present (deploy template)

Keep these checks strict in CI. They prevent partial runs with ambiguous failures.

## Recommended enable path

1. Run templates manually with `workflow_dispatch`.
2. Confirm outputs and exit behavior.
3. Rename/remove `-template` suffix and add real triggers (`pull_request`, `push`, tags, etc.).
4. Tighten map checks using `kimen map lint --strict` in PR gates.

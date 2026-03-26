# Recommended Paths

Use one of these as your default operating pattern.

## 1) Local dev (single machine)

1. Initialize vault and set secrets.
2. Use maps/profiles for repeatable intent.
3. Run apps with `kimen project run`.

Example:

```bash
kimen vault init
echo -n 'shh' | kimen secret set api_key --stdin
mkdir -p .kimen/profiles
cat > .kimen/profiles/dev.kmap <<'EOF'
env API_KEY=api_key
EOF
kimen project run --profile dev -- ./your-app
```

## 2) CI deploy (bundle + preflight + scoped run)

1. Keep `vault.age` as ciphertext in repo/storage.
2. Open bundle in job.
3. Run `kimen doctor --strict` before deploy.
4. Deploy with `kimen project run`.

Reference template:

- `.github/workflows/kimen-deploy-template.yml`

## 3) Systemd-friendly runtime files

1. Render to deterministic runtime path.
2. Wire service to that path.
3. Keep permissions strict (`0700` dir, `0600` files).

Example:

```bash
kimen render \
  --systemd-service my-service \
  --runtime-dir /run \
  --file cfg.txt=api_key \
  --print-systemd-hints
```

## Operational defaults

- Use `--json` in automation.
- Use strict checks in CI:
  - `kimen map lint --strict`
  - `kimen doctor --strict`
- Prefer profile-driven commands over ad-hoc inline mappings.

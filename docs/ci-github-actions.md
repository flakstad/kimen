# GitHub Actions (CI) pattern

Kimen is local-first, but CI still needs secrets. The recommended model is:

- CI is its own **identity**
- CI decrypts an encrypted **bundle** locally in the job
- Kimen performs **projections** locally (env/files/run)

The transport that stores the encrypted bundle is “no-trust”: it only holds ciphertext.

## Example: decrypt a bundle in CI

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

4) In your workflow:

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
          ./kimen project run --env API_KEY=api_key -- your-build-step
```

Notes:

- This trusts GitHub Actions Secrets to hold the CI identity and/or vault passphrase.
- The bundle transport (repo/blob store) never needs plaintext access.
- Prefer `kimen project run` so secrets are realized only for the child process lifetime.

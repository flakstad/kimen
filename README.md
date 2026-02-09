# Kimen

Kimen is a local-first system for bringing secrets into usable runtime form. It stores secrets securely at rest and produces short-lived, context-specific projections—such as environment variables, rendered configuration files, or scoped execution contexts—only when they are intentionally requested.

Kimen treats secrets as *latent capabilities*, not static values: secrets remain inert by default, and only become “real” in a specific form when intent is expressed.

## Mental model

- **Secrets** are encrypted, inert source material.
- **Intent** is an explicit request for a secret *in a particular form*, for a particular use.
- **Projections** are the realized output (env vars, files, exec contexts, etc.) with an explicit lifetime.

## Does something like this already exist?

Short answer, honestly: **this exact thing does not really exist as a coherent tool**.

Pieces exist. The idea exists in fragments. But the system described here — local-first, projection-centric, intent-driven — is genuinely novel *as a whole*.

For a precise, fair map of the landscape and what’s novel about Kimen, see `docs/landscape.md`.

## Local-first, but what about teams and business models?

Kimen is designed to be complete and useful with **no server**. If there’s a business play, it’s in optional coordination (sync, membership, auditing) rather than centralizing runtime or turning Kimen into a hosted vault.

See `docs/business.md`.

## Quickstart (current CLI)

Set a vault location (optional) and initialize:

```bash
export KIMEN_VAULT="$HOME/.config/kimen/vault.db"
kimen vault init
```

Add a secret:

```bash
echo -n 'shh' | kimen secret set api_key --stdin
```

Run a command with projected secrets (env + files):

```bash
kimen project run --env API_KEY=api_key --file config.txt=api_key -- printenv API_KEY
```

Export/import the vault as an age-encrypted bundle (useful for CI and “no-trust” sync transports):

```bash
kimen bundle seal --out vault.age --recipient age1...
kimen bundle open --in vault.age --out-vault "$KIMEN_VAULT" --identity path/to/age.key --overwrite
```

See also `docs/ci-github-actions.md`.

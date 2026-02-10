# Threat model and safety notes

Kimen is designed to reduce *accidental exposure* of secrets during development and operations by making secret realization explicit, scoped, and short-lived.

This document describes what Kimen helps with, what it does not, and common footguns.

## What Kimen is good at

Kimen meaningfully reduces these common failure modes:

- Long-lived shell exports (`export API_KEY=...`) that linger across commands/sessions
- Secrets pasted into terminals or committed into `.env` files
- “Convenience” scripts that print secrets to stdout
- Config files rendered into repos or shared directories by accident (when using temp dirs / strict perms)

## What Kimen is not designed to protect against

- A compromised machine (malware, hostile local user, root access)
- A compromised process that can read its own environment/files
- Secrets exfiltrated by the command you run (logging, telemetry, crash reports)
- OS-level memory inspection or debugging on the host

Kimen reduces exposure windows. It does not make secrets “safe to run untrusted code with”.

## Runtime surfaces (real risks)

Even with Kimen, secret material can leak through:

- **Environment variables**: visible to the child process; can end up in crash dumps, debug tooling, or logs depending on the program.
- **Files**: safer than env in many cases, but can be captured by backups/snapshots or accidentally copied. Always treat rendered secret files as radioactive.
- **Stdout/stderr**: Kimen avoids printing values by default, but the command you run may print them.

## Recommended practices

- Prefer `kimen run` over manual `secret get`.
- Prefer file projections for secrets that don’t need to be env vars.
- Prefer temp directories (default) for file projections; only use persistent dirs intentionally.
- In CI, decrypt bundles inside the job workspace and keep artifacts clean.

## Long-running services and agents

`kimen run` scopes projections to the *start* of a process. If the process runs for days, the secrets are effectively long-lived for that process.

For services, Kimen is best used as:

- a local operator tool (dev + one-shot commands), or
- a deploy-time renderer (`envfile` / `render`) paired with disciplined lifecycle management, or
- a projection layer on top of a platform secret manager for rotation/renewal.


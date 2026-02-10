# Alternatives and adjacent tools

Kimen is not trying to replace every secret manager. It’s a local-first **projection** tool: make secrets usable in runtime form (env/files/exec) with explicit intent and minimal exposure.

This doc compares Kimen to adjacent tools and helps decide when to use which.

## Quick comparison (high level)

| Tool | Strengths | Where it differs from Kimen |
|---|---|---|
| **Bitwarden / 1Password (password managers)** | Web logins, autofill, sharing, recovery, great UX | Kimen is not a login manager; it’s built for runtime projections and automation |
| **Vault / cloud secret managers** | Central policy, rotation, audit, dynamic secrets, HA | Kimen is local-first and projection-centric; it avoids making runtime depend on a server |
| **sops + age** | Git-friendly encrypted config files, common in infra | Kimen’s core is “run/render” lifecycle + planning; sops is file-centric and usually needs glue scripts |
| **pass (password-store)** | Simple local secrets + git sync | Kimen is more runtime-oriented (run/render/plan/profiles/maps); pass is value-centric |
| **dotenv / direnv** | Environment shaping for dev | Kimen adds encrypted storage and explicit projection lifetimes (and avoids long-lived shell env) |

## Where Kimen shines

- Local development loops: project secrets into a REPL or dev server without exporting global env vars.
- Operator workflows: run one privileged command with scoped secrets.
- CI: decrypt a bundle locally inside the job, then use `kimen run` for minimal exposure.
- Agent runtimes: run an agent with only the credentials it needs for that run.

## Where Kimen is not the best fit (today)

- You want a team password manager / autofill / recovery UX: use Bitwarden/1Password.
- You need enterprise secret rotation/audit/dynamic credentials as a platform feature: use Vault or cloud secret managers.
- You want “secrets delivered into Kubernetes workloads” as a standard pattern: use existing K8s/secret-manager integrations.

## A useful mental model

Kimen can coexist with other tools:

- Another system can be the **source of truth** for secrets.
- Kimen can still be the **projection engine** that shapes and scopes those secrets at runtime.

That’s often the least-disruptive adoption path.


# When to use Kimen

Use Kimen when you want:

- Local-first workflows (works without a server)
- A CLI-first tool for developers/operators
- Secrets scoped to a single process run (`kimen run`)
- Secrets materialized into files with strict perms (`kimen render`, `kimen envfile`)
- A safe planning step that never prints secret values (`kimen plan`)
- Repeatable intent via maps/profiles (`--map`, `--profile`)

Use something else when you want:

- Password-manager UX (autofill, browser integration, recovery)
- Centralized enterprise secret distribution and policy enforcement
- Built-in secret rotation and compliance reporting as primary features
- First-class Kubernetes secret delivery patterns (sidecars/operators/injectors)

Kimen can still complement those systems as a projection tool.


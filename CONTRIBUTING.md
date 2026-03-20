# Contributing to Kimen

Kimen is still early-stage. Small, focused changes are easier to review and safer
to land than broad refactors.

## Development setup

Recommended local tools:

- JDK 21 or newer
- Clojure CLI
- Babashka (`bb`)

Typical commands:

```bash
bb test
bb itest
bb e2e-sync-all
bb native
```

If you prefer plain Clojure CLI for unit tests:

```bash
clojure -M:test
```

## Project layout

- `src/`: CLI, runtime, and library implementation
- `test/`: unit and CLI tests
- `docs/`: user-facing reference and design docs
- `resources/kimen/scaffold_templates/`: generated workflow templates

## What good contributions look like

- Keep changes narrow and intentional.
- Preserve typed exit codes and machine-readable output.
- Update docs when CLI behavior or examples change.
- Add tests for behavior changes, especially around CLI contracts.

## Before opening a PR

Run the smallest relevant test set locally. For behavior changes, prefer running:

```bash
bb test
```

When you touch projection, sync, or template flows, also run the broader suites that
cover that area.

## Release notes

Tagged releases are built by GitHub Actions from tags shaped like `vYYYY.M.P`.
The release workflow publishes:

- `kimen`
- `kimen.jar`
- `SHA256SUMS`

If your change affects install flow, release assets, or documented commands, update
`README.md` and the relevant docs under `docs/`.

# Session Agent MVP

This document specifies the first real slice of `kimen session`: a local,
user-scoped unlock agent that removes repeated passphrase prompts without
turning Kimen into a networked secrets service.

It is a design target for implementation work. It does **not** describe shipped
behavior yet.

## Goals

- Keep the vault unlock secret in memory for a bounded local session.
- Reuse that session across existing CLI commands without changing their public
  shape.
- Preserve Kimen's local-first model: no remote service, no central authority,
  no long-lived daemon required for basic usage.
- Make the happy path explicit and inspectable through `session start|status|lock|stop`.

## Non-goals

- Multi-user or networked secret serving.
- Replacing OS keychains or external password managers.
- Per-secret fine-grained authorization or policy enforcement.
- Rotation / watch / reload automation in the MVP.
- Windows support in the first slice.

## Operating model

The session agent is a background process bound to a Unix domain socket inside a
Kimen-owned directory with strict local permissions.

The agent:

- receives an unlock passphrase once at startup over stdin
- keeps that passphrase in memory only
- serves local client requests over a Unix socket
- exits automatically after an idle TTL or explicitly via `stop`

The rest of the CLI remains intact. Commands that need a passphrase may consult
the active session before falling back to config or prompting.

## Command surface

### `kimen session start`

Starts or refreshes a local session for one resolved vault path.

Behavior:

- resolves the target vault path using normal precedence
- resolves a passphrase using the existing non-session rules
- verifies the passphrase can unlock the vault before daemonizing
- spawns a background agent process
- writes session metadata under the session directory
- prints the active session status

Common flags for the MVP:

- `--vault <path>`: target vault
- `--ttl <duration>`: idle TTL, default `15m`
- `--json`: machine-readable output

If an unlocked session already exists for the same vault path, `start` should
refresh TTL/metadata rather than starting a second agent.

If a session exists but is locked, `start` should reuse the same session record
and send a fresh unlock to the agent.

### `kimen session status`

Reports whether a session exists for the resolved vault path and whether it is
currently usable.

Expected fields:

- `running`
- `locked`
- `vault_path`
- `socket_path`
- `pid`
- `ttl_ms`
- `idle_ms_remaining`
- `last_seen_at`

### `kimen session lock`

Clears the passphrase from memory but leaves the agent and socket in place.

After `lock`:

- `status` reports `locked=true`
- ordinary commands must not receive a passphrase from the session
- `session start` may re-unlock the same session

### `kimen session stop`

Terminates the session agent and removes socket/metadata files.

After `stop`:

- `status` reports `running=false`
- ordinary commands fall back to normal passphrase resolution

### Internal command: `kimen session serve`

This is an internal subcommand used by `session start` to launch the background
agent. It is not a primary user-facing surface.

## Passphrase precedence

The session agent must not override explicit passphrase sources.

MVP precedence for commands that need a passphrase:

1. `KIMEN_PASSPHRASE`
2. explicit flags: `--passphrase-cmd`, `--passphrase-stdin`
3. active unlocked session for the resolved vault path
4. `kimen config unlock ...`
5. interactive prompt

This keeps scripted behavior deterministic while removing the repeated prompt
tax for interactive local usage.

## Session layout

The session root should live under the Kimen config directory:

`<default-kimen-dir>/sessions/<vault-hash>/`

Recommended contents:

- `agent.sock`: Unix domain socket
- `meta.json`: current session metadata
- `pid`: optional plain-text pid file for debugging

`<vault-hash>` should be a stable hash of the canonical vault path so multiple
vaults do not collide and no raw secret-adjacent path needs to become the
directory name.

Permissions:

- session root: `0700`
- metadata/pid files: `0600`
- socket inherits owner-only access from directory placement

## Socket protocol

Transport: Unix domain socket, one JSON request/response per connection.

Requests:

```json
{"op":"ping"}
{"op":"status"}
{"op":"get_passphrase"}
{"op":"lock"}
{"op":"stop"}
{"op":"unlock","passphrase":"...","ttl_ms":900000}
```

Responses:

```json
{"ok":true,"running":true,"locked":false}
{"ok":true,"passphrase":"..."}
{"ok":false,"reason":"session_locked"}
{"ok":false,"reason":"session_expired"}
{"ok":false,"reason":"session_not_running"}
```

The response shapes should stay intentionally small. This is a local control
protocol, not a public API surface.

## TTL semantics

- TTL is idle-based, not absolute.
- The countdown resets on successful session-backed client use.
- When TTL expires, the agent clears the passphrase and exits.
- `status` should report remaining idle time when the session is alive.

Default TTL: `15m`

Why idle TTL:

- it matches the local interactive use case
- it limits exposure for forgotten background sessions
- it avoids forcing re-entry during a burst of repeated commands

## Security posture

The session agent improves ergonomics, not the base machine trust model.

It still assumes:

- same-user local trust
- no hostile root or malware on the host
- no expectation that long-running processes are magically safe

The MVP must avoid these anti-patterns:

- passing the vault passphrase in argv
- storing the passphrase on disk
- exposing a TCP listener
- weakening explicit passphrase sources for automation

Startup handoff rule:

- parent resolves and validates the passphrase
- child receives it once on stdin
- child keeps it only in memory

## Integration points

The implementation work should be split along these boundaries:

1. lifecycle and socket daemon
2. passphrase resolution integration
3. future projection leases and watch/reload features

The initial daemon may return the passphrase itself to local clients. A later
iteration can replace that with narrower projection leases without changing the
high-level `session` command surface.

## Testing expectations

The implementation children should cover:

- start/status/lock/stop happy paths
- idle expiry
- same-vault session reuse
- explicit passphrase source precedence over session
- fallback behavior when no session exists
- cleanup of stale socket/metadata state

## Follow-on work

These belong after the MVP:

- projection leases instead of returning raw passphrase text
- `render --watch` / service reload hooks
- Windows-compatible transport
- richer session introspection and metrics

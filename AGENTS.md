# Kimen Agent Guide (Clojure Rewrite)

This file defines repo-local guidance for agents working on the Clojure rewrite of Kimen.

## Intent

- Rewrite Kimen in Clojure with the simplest possible design.
- Keep behavior trustworthy by leaning on the existing Go test suite as the oracle.
- Preserve a clear path to multiple outputs:
  - Clojure library
  - JVM JAR
  - Babashka-invokable CLI/script
  - GraalVM native binary

## Design Principles

- Prefer simple over easy.
- Decomplect: separate concerns in data and time.
- Use plain data structures for contracts.
- Keep functions small, explicit, and boring.
- Avoid cleverness and incidental abstractions.
- Add dependencies only when they clearly reduce total complexity.

## Architecture: FC/IS

Favor Functional Core / Imperative Shell as a hard boundary.

- Functional core:
  - Pure functions.
  - Data in, data out.
  - Deterministic, testable behavior.
  - No file/network/process/env/time side effects.
- Imperative shell:
  - CLI parsing, stdout/stderr, file IO, process exec, env, clock, randomness.
  - Calls core functions and interprets returned data/effects.

Keep boundary crossing explicit in function signatures and namespace layout.

## Repository Shape (target)

- `src/kimen/...`: runtime code.
- `test/...`: Clojure tests, including compatibility tests.
- `bb.edn`: fast local dev/test task entrypoints.
- `deps.edn`: JVM/library/build dependency graph.
- `build.clj`: JAR/native build tasks.

## Babashka + JVM Strategy

- `bb` should be the default dev loop for run/test where practical.
- Keep core namespaces babashka-compatible.
- Isolate JVM-only code (native-image build integration, advanced crypto/storage details) behind adapters.
- Do not require babashka libraries (`babashka.fs`, `babashka.process`) for core runtime behavior unless explicitly chosen later.

## Compatibility Strategy (Go as Oracle)

- Port feature-by-feature.
- Recreate existing Go CLI behavior in Clojure:
  - exit codes
  - JSON envelope shapes
  - reason codes
  - stderr/stdout behavior
- Prefer black-box compatibility tests that run scenarios against both implementations when useful.
- Do not "improve" behavior mid-port unless explicitly decided and documented.

## Testing Rules

- Add/port tests before or with implementation changes.
- Keep pure core tests dense and fast.
- Keep shell/integration tests focused on contracts and boundaries.
- When behavior differs from Go intentionally, document it in the test and docs.

## CLI Conventions

- CLI layer should mostly decode args, call domain/core, and encode outputs.
- Keep machine-facing output stable and explicit.
- Preserve automation contract semantics as first-class behavior.

## Vault Direction

- Existing Go vault format compatibility is not required for runtime.
- Build a clean vault-v2 with clear interfaces and tests.
- Keep vault implementation modular so extraction to a separate project is possible later, but do not extract prematurely.

## Native Image Readiness

- Treat native-image as a supported target, not an afterthought.
- Avoid reflective interop in hot/runtime paths.
- Keep dynamic runtime loading and eval-heavy patterns out of core runtime paths.
- Keep native-image config/build concerns in build tooling, not domain logic.

## Dependency Policy

- Default posture: Clojure + Java standard library.
- Any new dependency must justify:
  - why stdlib is insufficient
  - what complexity it removes
  - impact on bb/JVM/native targets

## Reference Project

When in doubt, use `../ro/ro` as a style and structure reference for:

- command organization
- FC/IS separation
- native build workflow
- practical Clojure ergonomics

## Clojure REPL Evaluation

The command `clj-nrepl-eval` is installed on your path for evaluating Clojure code via nREPL.

Discover nREPL servers:

`clj-nrepl-eval --discover-ports`

Evaluate code:

`clj-nrepl-eval -p <port> "<clojure-code>"`

With timeout (milliseconds):

`clj-nrepl-eval -p <port> --timeout 5000 "<clojure-code>"`

The REPL session persists between evaluations; namespaces and state are maintained.
Always use `:reload` when requiring namespaces to pick up changes.

## Clojure Parenthesis Repair

The command `clj-paren-repair` is installed on your path.

Examples:

`clj-paren-repair <files>`

`clj-paren-repair path/to/file1.clj path/to/file2.clj path/to/file3.clj`

IMPORTANT:

- Do not try to manually repair parenthesis errors.
- If you encounter unbalanced delimiters, run `clj-paren-repair` on the file instead.
- If the tool does not work, report that the delimiter error must be fixed manually.

`clj-paren-repair` automatically formats files with `cljfmt` when it processes them.

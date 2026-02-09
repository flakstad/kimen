# Does something like Kimen already exist?

Short answer, honestly: **this exact thing does not really exist as a coherent tool**.

Pieces exist. The *idea* exists in fragments. But the system described here — local-first, projection-centric, intent-driven — is **genuinely novel as a whole**.

This document tries to be precise and fair about that claim.

## The landscape, clearly mapped

### 1) Traditional secret *stores* (Vault, 1Password, Bitwarden, etc.)

What they do well:

- Encrypt secrets at rest
- Control who can read a value
- Sync across devices/teams
- Provide CLIs and integrations

What they fundamentally assume:

- A secret is a **value**
- The primary operation is **get**
- Runtime shaping is someone else’s problem

Even when these tools add features like env injection, templating, or CLI helpers, those are usually convenience layered on top of retrieval, not first-class concepts.

They answer:

> “Who may see this secret?”

They generally do **not** answer:

> “How should this secret exist right now, for this use?”

That distinction matters.

### 2) Cloud / infra secret managers (Vault, SSM/Secrets Manager, sidecars)

These get closer in some respects (leases, TTLs, identity-based access, templating), but still miss the core idea.

Common traits:

- **Cloud-first**
- Infrastructure-coupled
- Oriented around **distribution** and **platform integration**
- Designed for long-running environments and centralized policy

Their default assumption tends to be:

> “Secrets live in the platform; workloads come to them.”

Kimen’s assumption is inverted:

> “Secrets live locally; projections come and go.”

That inversion is philosophical *and* practical.

### 3) dotenv / direnv / sops / age-based flows

These are important and widely used, especially for local workflows.

They handle:

- Local-first ergonomics
- Encrypted files (sops/age)
- Environment shaping (dotenv/direnv)

But they are typically:

- **File-centric**
- Mostly static
- Weak on runtime semantics (intent, lifetime, cleanup)
- Not projection-first (projection is an output artifact, not the core model)

They tend to stop at:

> “Here is the file/env you need.”

They don’t usually model:

- *why* it exists (intent)
- *how long* it should exist (lifetime)
- *what form* it should take *per use* (projection selection)

### 4) Capability systems (keychains, tokens, OAuth scopes)

Some systems do think in terms of scoping and time-bounded access:

- Capabilities / bearer tokens
- Fine-grained scopes
- TTLs and refresh

But they’re typically:

- Narrow (one domain, one platform)
- Not composable across arbitrary developer workflows
- Not user-programmable in the “shape secrets into runtime” sense
- Not local-first developer tools

Kimen generalizes the underlying idea:

> secrets as capabilities that are **realized**, not merely **retrieved**

That combination is rare.

## What’s actually novel here

The novelty is **not** encryption.
The novelty is **not** projections in isolation.
The novelty is **not** local-first on its own.

The novelty is the combination:

### 1) Projections are the primary abstraction

Not an add-on. Not a helper. The core mental model is: “what projection do I need right now?”

### 2) Secrets are inert by default

Nothing happens until:

- intent is expressed
- context is supplied
- a projection is requested

This is different from the default “store + get” framing.

### 3) Runtime is first-class

The system is designed around:

- processes
- commands
- agents
- ephemeral execution

Not around browsing, copying, and pasting.

### 4) Local-first without being toy-scale

Many local-first tools stop at convenience; many “serious” secret systems stop at cloud platforms.

Kimen aims at the empty middle: a local-first tool with explicit lifecycle and runtime semantics.

## Why this gap exists

This idea isn’t missed accidentally. It sits between disciplines:

- security tooling
- developer ergonomics
- systems design
- agent/process runtimes

Vendors sell storage. Enterprises buy compliance. Developers duct-tape runtime.

Very few systems ask:

> “What is the correct lifecycle of a secret in a running system?”

## Is it risky because it’s novel?

Novel doesn’t have to mean reckless. The conceptual direction here is conservative:

- reduce exposure
- reduce duplication
- reduce ambient authority
- reduce accidental leaks

It aligns with:

- least privilege
- capability security
- calm software
- local-first durability

## The honest verdict

- Does an identical tool exist? **No.**
- Do partial ideas exist elsewhere? **Yes, scattered and incomplete.**
- Is this a weird idea? **No — it’s obvious once you see it.**

If, in a few years, someone summarizes Kimen as:

> “the tool that treats secrets as latent capabilities and projects them into runtime”

…that should feel inevitable, not trendy.

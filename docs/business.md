# Local-first, but is there a business play?

This is the right question to ask **before** writing too much code. The honest answer is: **yes, there is a business play here** — but it’s not the obvious one, and it only works if you protect the local-first core.

This doc lays out the business-shaped surface area without salesmanship.

## First: the hard truth

If Kimen is only:

- a local encrypted store
- a CLI
- projections into env / files / commands

…then it will almost certainly remain:

- open source
- widely loved
- financially small

That’s not a failure — it’s the natural shape of a good primitive.

So the real question is:

> Where does money enter without corrupting the design?

## The invariant you must protect

Lock this in:

> Kimen must be complete and useful with **no server**.

If that stops being true, you lose the local-first philosophy *and* the trust boundary, and you end up as “another Vault-lite”.

Everything below respects this invariant.

## The real leverage point: sharing, not storage

Money tends to appear when secrets need to be **shared, coordinated, or audited across boundaries**.

The value is in coordination, not in holding the raw secret material.

## Three viable business shapes (ranked by alignment)

### 1) Hosted sync & coordination (best fit)

What stays local:

- encryption
- projections
- policy evaluation
- runtime behavior

What a server can do:

- sync encrypted metadata + blobs
- device / team membership
- key exchange assistance
- conflict resolution
- optional audit aggregation

The mental model should be:

> “Git remote for Kimen vaults”, not “Kimen in the cloud”.

Why it works:

- teams already pay for this pattern (GitHub, 1Password, etc.)
- you don’t need to touch raw secrets
- you don’t execute projections
- you don’t become a critical runtime dependency

The server stays optional, replaceable, and boring.

### 2) Team / enterprise features (without centralizing runtime)

Examples:

- multi-person policy approval
- shared vaults with role separation
- rotation orchestration across machines
- compliance exports
- break-glass workflows

These can be opt-in layers: helpful once you cross from “me” to “us”, but non-essential for a solo local-first workflow.

### 3) Paid support / distribution / certification

This is smaller but clean:

- paid binaries
- long-term support builds
- consulting for high-assurance setups
- internal deployment help

This works best if Kimen becomes infrastructure people trust, not hype-driven software.

## What about a central SaaS vault?

Bluntly: it’s the wrong direction.

- duplicates existing products
- undermines the local-first philosophy
- pushes you into compliance-heavy territory
- weakens the core concept (projection over storage)

It risks turning a novel tool into a generic one.

## What about peer-to-peer sync?

P2P is interesting as an option, not a requirement.

It can make sense if:

- vaults are end-to-end encrypted
- keys never touch a server
- users can choose transport (LAN, Tailscale, etc.)

But it’s harder to explain and debug, and harder to monetize directly. Treat it as an advanced mode or a future option — not the primary story.

## The sweet spot

The most aligned model is simple:

> Local-first by default. Paid coordination when you cross from “me” to “us”.

Kimen remains complete without money; money appears when friction between people appears.

## A framing that keeps you honest

This statement is a good north star:

> Kimen is fully functional as a local tool. Optional hosted services may exist for teams that want shared state and coordination, but runtime behavior is always local.

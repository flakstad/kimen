# Security Policy

Kimen handles secrets, so security issues should be reported privately.

## Reporting a vulnerability

Please email `andreas@breyta.ai` with:

- a short description of the issue
- impact and affected command/flow
- reproduction steps or a proof of concept
- any suggested mitigation if you have one

Please do not open a public GitHub issue for a vulnerability that could expose
real secrets or weaken expected protections.

## Scope

Relevant reports include issues such as:

- secrets printed unexpectedly
- vault or bundle encryption/decryption flaws
- projection leaks outside the documented contract
- unsafe permission handling for rendered secret files
- sync flows that can corrupt or disclose encrypted data unexpectedly

## Supported versions

Kimen is still early-stage. Security fixes are handled on a best-effort basis for:

- the latest release
- current `main`

Older revisions may not receive backports.

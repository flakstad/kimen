# Git hooks

This repo keeps git hooks in `.githooks/` so they can be versioned.

To enable them locally:

```bash
git config core.hooksPath .githooks
chmod +x .githooks/pre-commit
```

To bypass the hook for a single commit:

```bash
git commit --no-verify
```

Or set `KIMEN_SKIP_PRECOMMIT=1` for your shell session.

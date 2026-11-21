# Guardrail Hooks

Deterministic checks that run before commit and before push. They are
specified here as the contract; the checks below are written to be run by
an agent or a human verbatim. To activate them as real git hooks, copy the
commands into `.git/hooks/` locally — they are intentionally not installed
by default, and `.git/` is never committed.

## Pre-commit checks

1. **Secrets scan** — staged diff must not match credential patterns:
   `git diff --cached | grep -iE 'portal|mac[: ]|password|token|api[-_]?key|BEGIN .*PRIVATE KEY'`
   Any hit that is a real value (not a fixture placeholder) blocks the
   commit.
2. **Forbidden files** — staged files must not include keystores, `.env`,
   or anything under `.tools/`:
   `git diff --cached --name-only | grep -E '\.(keystore|jks)$|^\.env|\.tools/'`
3. **App identity** — `applicationId` in the build config must still be
   `xyz.billybishop4.fleet.tv`.
4. **Version discipline** — version name/code may change only in a commit
   whose message starts with `Release`.

## Pre-push checks

1. **Snapshot integrity** — if the push touches `releases/`, verify its
   SHA256SUMS against the tree before pushing.
2. **Review gates** — the branch has a @code-reviewer verdict of approve
   and a @security-reviewer pass recorded in the PR or commit thread.
3. **No default profiles** — the change must not add or provision an IPTV
   profile by default.

## Failure behavior

A failed check stops the operation and reports the specific check and
evidence. Only the user can waive a failure, and the waiver is recorded in
the commit or PR thread.

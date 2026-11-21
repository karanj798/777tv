# Skill: Feature Development Pipeline

The standard path for every feature on this fork.

## 1. Plan (@planner)

- Produce the plan per `personas/planner.md`.
- **Gate:** user approves the plan before any code is written.

## 2. Implement (@implementer)

- Branch: `feature/<short-name>` off `main`.
- Implement per plan; run fixture/performance suites on the fleet-tv-dev
  replicas (see `remote/README.md`) where the change touches playback,
  catalogs, or Stalker.
- Preserve app data in every test install (in-place update, never wipe).

## 3. Review (@code-reviewer)

- Full checklist pass. Findings fixed by @implementer, then re-reviewed.

## 4. Security review (@security-reviewer)

- Full checklist pass. Any fail blocks merge.

## 5. Merge and record

- Merge to `main` with a commit message stating what and why.
- If the change is release-bound, hand off to
  `skills/release-snapshot.md`.

## Stop conditions

- A step discovers the plan is wrong → return to step 1.
- An always-on rule would be broken → stop and ask the user.

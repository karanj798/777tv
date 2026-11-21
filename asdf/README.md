# ASDF — Agent Specifications for Development Framework (777tv fork)

This directory is the single source of truth for how AI agents develop
features on this fork of 777tv. It adapts the ASDF model (centralized agent
specifications: always-on rules, file-scoped conventions, personas, skills,
on-demand commands, guardrail hooks) to this codebase.

The upstream ASDF project (mcpmarket.com/server/asdf) publishes no installable
code — its repository is unreachable — so this tree implements the model
directly, in-repo, as plain Markdown any agent can read.

## How to use this

1. Read `rules/always-on.md` before any work. It outranks convenience.
2. Read `rules/file-scoped.md` for the areas you will touch.
3. Work the pipeline in `skills/feature-development.md`:
   @planner → @implementer → @code-reviewer → @security-reviewer.
   Personas are defined in `personas/`.
4. On-demand commands are listed in `commands.md`.
5. Guardrails in `hooks/guardrails.md` are checked before every commit
   and push.

## Layout

- `rules/` — always-on rules and file-scoped conventions
- `personas/` — single-responsibility agent profiles
- `skills/` — reusable, repeatable workflows
- `commands.md` — on-demand commands a human can invoke
- `hooks/` — deterministic guardrail checks (spec + activation notes)

## Relationship to AGENTS.md

The repo-root `AGENTS.md` carries upstream (Het's) workflow authorizations
and history. This ASDF tree carries the fork's development rules. Where they
conflict on safety (credentials, app data, app identity), the stricter rule
wins. Where they conflict otherwise, AGENTS.md wins for upstream matters and
this tree wins for work on this fork.

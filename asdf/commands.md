# On-Demand Commands

Invoke these in plain language; the agent runs the matching workflow.

- **/plan-feature <description>** — @planner produces a plan for approval.
  No code is written.
- **/implement** — @implementer builds the currently approved plan on a
  `feature/` branch.
- **/review** — @code-reviewer audits the current branch against its plan.
- **/security-check** — @security-reviewer runs the secrets/security
  checklist on the current diff.
- **/release <version>** — run the release snapshot skill for a version.
- **/status** — report pipeline state: current branch, plan status,
  review verdicts, and what is blocked on the user.

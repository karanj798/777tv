# 777tv Android TV workflow (formerly Fleet)

> **Fork addition (karanj798):** Feature development on this fork follows the
> ASDF specification tree in [`asdf/`](asdf/README.md) — always-on rules,
> file-scoped conventions, personas (@planner, @implementer, @code-reviewer,
> @security-reviewer), skills, commands, and guardrail hooks. Read
> `asdf/rules/always-on.md` before starting any feature work.

- Het now authorizes local Mac development/builds/tests and direct deployment
  to the TCL for testing. Preserve watch history, add-ons and other app data. ExoPlayer is now the only player; no engine
  setting remains. The app is named 777tv; its application ID stays unchanged for
  in-place updates. The user subsequently requested deleting the saved IPTV
  profile on the TCL and shipping no default profiles. The profile was deleted;
  do not restore or provision one unless explicitly asked. Leave STBEmu unchanged.
  Existing remote runs can finish; use local or remote tools as useful.
- `remote/README.md` documents the three `fleet-tv-dev` build/test replicas.
  Use native Node fetch for Shellbox/Fleet access; no browser is needed.
- For the current Android TV updates, Het's later "Install it on the tcl now"
  authorizes installation and focused checks on the physical TCL. Preserve app
  data and saved profiles. Run automated fixture/performance suites on workers.
- Use generated media and Stalker fixtures remotely. Never upload real IPTV
  credentials, `.tools/stalker-private.json`, signing keys, `.env` or the private
  worker-access configuration in source archives, logs or app artifacts.
- Keep the existing `shellbox` app and its sessions intact. Development uses
  the separate, password-protected `fleet-tv-dev` provider app.

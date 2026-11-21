# File-Scoped Conventions

Rules that apply only when working in the matching area.

## `app/` — Kotlin sources

- Native views and RecyclerView; no Compose or JS UI unless the user
  explicitly changes this rule.
- Playback goes through Media3 ExoPlayer only. Hardware decoding stays the
  default.
- TV-first UX: D-pad navigable, focus always visible, OK selects, Back
  behaves predictably. Check focus order for every new screen.
- Keep rows fixed-size where the existing UI does (catalog shelves, episode
  and stream rows) to avoid layout jank on low-end TVs.

## Stremio add-on code

- Manifest install, catalogs, metadata, search and streams follow the
  Stremio add-on protocol as already implemented. Preserve support for
  add-on request headers.
- Direct HTTP(S) stream links only.

## Stalker / IPTV code

- Profiles carry required and optional device-identity fields; keep the
  connection test working for every profile change.
- Never hardcode a portal, MAC, or credential. Fixtures only in tests.
- Live playback has no seek bar; preview on first OK, fullscreen on second
  OK — preserve that interaction model.

## `build.gradle.kts`, `settings.gradle.kts`, `gradle/`

- Version name and version code change together, and only in a release
  change — never inside a feature change.
- New dependencies need a stated reason in the commit message.

## `releases/`

- Treat as append-only history. Never edit a past snapshot; a fix ships as
  a new version snapshot with regenerated checksums.

## `scripts/`, `remote/`

- `remote/README.md` documents the fleet-tv-dev build/test replicas; follow
  it for remote runs. Keep the existing `shellbox` app and its sessions
  intact; development uses the separate password-protected fleet-tv-dev app.
- Scripts must not embed credentials; read them from local, untracked
  config.

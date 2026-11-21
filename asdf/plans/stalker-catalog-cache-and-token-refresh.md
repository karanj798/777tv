# Plan: Stalker catalog cache (TTL + reload) and throttled token refresh

Persona: @planner · Status: approved by user 2026-11-21 ("Approve plan — 3 day TTL")
TTL decision: 3 days.

## Goal and user-visible behavior

Opening Live TV for a Stalker portal should stop re-downloading the full
catalog every time. Genres and channels are fetched once, cached on the
TV, and reused until the cache is **3 days old** — then the next open
refetches in the background of a normal load. The existing
**Refresh channels** button becomes a true cache reload: it discards the
cached catalog for that provider and fetches fresh, on demand.

Separately, when the portal rejects a bearer token as expired/invalid,
the app already retries once with a new handshake — but it does so
immediately, and every operation (channels, guide, watch) authenticates
afresh, so a flaky portal can be hit with a burst of handshake requests.
This change puts a mandatory pause before any re-authentication and a
minimum interval between handshakes, so the app never bombards the
portal with token-generation requests and risks a rate limit / block.

## What the code does today (verified 2026-11-21)

- `Repository.channels()` has **no cache at all** — every call goes to
  the network. The other Repository caches are in-memory only; the guide
  cache is the only one with a TTL (5 min).
- `StalkerClient.session()` authenticates afresh for **every** operation
  (`Stalker.kt`: "Each operation authenticates afresh; short-lived tokens
  never reach disk"). One load = handshake + `get_profile` +
  `get_genres` + `get_all_channels`. On Karan's portal that last response
  alone is ~8.4 MB / 5,842 channels, with a 15 s timeout.
- Token expiry is detected as: HTTP 401/403, a `js` result of
  false/null, or the literal `"Authorization failed."` — all throw
  `ExpiredPortal`. `session()` retries **exactly once, immediately**
  (no delay, no backoff, no minimum handshake interval). The
  `operations` Mutex already serializes operations, so handshakes never
  run in parallel — they just run back-to-back.
- `LiveScreen` already has a "Refresh channels" action → `FleetModel.retry()`
  sets `channelProvider = null` and reloads. Today that reload is always
  a full network fetch; with a cache it must become the force-refetch path.
- `Store` is SharedPreferences (`fleet-tv`); provider credentials are
  stored AES-GCM encrypted via AndroidKeyStore. An ~8 MB catalog must
  **not** go into SharedPreferences (load/parse cost on every read) —
  see Step 1.

## Affected areas

- `Stalker.kt` — session/token lifecycle: re-auth delay, minimum
  handshake interval, single-retry cap. (File-scoped: Stalker/IPTV —
  keep the connection test working, never hardcode portal/MAC/credentials.)
- `Repository.kt` — `channels(provider, forceRefresh)` consults the
  catalog cache before calling `StalkerClient`.
- `Store.kt` — new catalog-cache storage (app-private file per provider,
  see Step 1) + saved-at timestamp.
- `FleetModel.kt` / `LiveScreen.kt` — Refresh channels plumbs
  `forceRefresh = true`; normal channel loads use the cache.
- `app/src/test/.../StalkerTest.kt` (+ a new cache test) — fixtures only,
  no real portal data.
- No build.gradle / version changes (feature change, not a release).
  No new dependencies — Kotlin + org.json + kotlinx.coroutines already
  in the project. Playback untouched: ExoPlayer path, `create_link`,
  and guide fetching are unchanged except for the token timing in Step 3.

## Steps (each independently testable)

1. **Persistent catalog cache in Store.**
   - One cache entry per provider, keyed by provider id + a hash of
     portal URL and MAC (so editing a profile to a different portal/MAC
     can never serve the old portal's channels; the hash is of values
     already stored encrypted — the cache file itself contains no MAC,
     no token, no credentials).
   - Contents: genres map (id → title) and the channel list
     (id, name, group, logo, command, number — the fields `Session.channels()`
     already builds), plus `savedAt` epoch ms.
   - Storage: a file in app-private internal storage (`filesDir`),
     written atomically (temp file + rename) — not SharedPreferences.
     Security reviewer to rule on whether the channel `command` strings
     require the same AES-GCM treatment as providers; default proposal:
     app-private file, same as other app data, no token ever written.
   - TTL: **3 days** from `savedAt`. Fresh → parse and return, zero
     network. Stale or missing → fetch, then rewrite the cache.
   - Corrupt/unparseable cache file → treat as missing, refetch, never crash.
2. **Repository + UI wiring.**
   - `Repository.channels(provider, forceRefresh = false)`: fresh cache
     and not forced → return cached list. Otherwise fetch via
     `StalkerClient` and update the cache.
   - `FleetModel.retry()` on the Channels route and the LiveScreen
     "Refresh channels" button → `forceRefresh = true` (this is the
     cache-reload button the user asked for; no new screen).
   - `StalkerClient.watch(refresh = true)` keeps working: its internal
     channel lookup should also respect `forceRefresh` semantics so a
     "channel no longer in the portal" refresh isn't served stale cache.
   - Connection test in FleetModel: use the cache path (it currently
     downloads the whole catalog just to count channels — with a fresh
     cache it becomes instant and network-free).
3. **Throttled token refresh in `StalkerClient.session()`.**
   - On `ExpiredPortal`, before the single allowed retry: wait
     **2 seconds** using `kotlinx.coroutines.delay` (the coroutine
     equivalent of the requested `time.sleep()` — non-blocking, and the
     `operations` Mutex is held throughout, so no other operation can
     sneak a handshake in during the pause).
   - Minimum interval between handshakes: track the last handshake time
     in `StalkerClient`; `authenticate()` delays until at least
     **2 seconds** have passed since the previous handshake, across
     operations. Normal sequential use rarely triggers this (a full
     catalog fetch takes longer); it only bites in retry bursts.
   - Hard cap stays: at most **one** re-authentication per operation
     (the existing `repeat(2)`), then the existing user-facing
     "Portal authorization failed" message. No retry loop is added.
   - If the portal answers 429, do not retry at all in that operation —
     surface the existing failure message. (Check in review whether
     `Http` exposes a Retry-After header worth honoring later; not in
     this change.)
   - Tokens still never reach disk or the cache — unchanged invariant.
4. **Tests.**
   - Cache: fresh entry returns without network (fixture Stalker server
     counts requests = 0); stale entry (savedAt = now − 3 days − 1 ms)
     refetches; forced refresh refetches despite fresh cache; corrupt
     cache file refetches; cache key changes when portal URL/MAC changes.
     Clock is injected so TTL tests don't sleep.
   - Token: first call 401/"Authorization failed." → exactly one new
     handshake, and the fake-clock/timestamp assertion shows the
     ≥ 2 s pause before it; two rapid operations show the minimum
     handshake interval enforced; persistent 401 → fails after one
     retry, total handshakes = 2, no more.
   - Existing StalkerTest suite (18 tests incl. endpoint discovery)
     must stay green. Tests run the same way as the last feature:
     pure-JVM cluster compiled with kotlinc + JUnit on this VM
     (Gradle cannot download through the VM proxy), then a full APK
     build via the local Maven mirror route.
5. **Manual TV check (delivered APK).**
   - First open after install: normal (slow) load. Kill and reopen the
     app: Live TV loads from cache, near-instant, no spinning.
   - Refresh channels: visibly reloads and picks up a portal-side change.
   - Leave a stale-cache scenario to the automated TTL test (no waiting
     3 days on a TV).

## Risks

- **App data:** cache is new, additive storage. It must not touch
  providers, favorites, hidden categories, progress, or add-ons.
  Deleting a provider should delete its cache file (add to Step 1).
  In-place APK update preserves the cache; uninstall wipes it, which
  is correct.
- **Staleness:** a provider can change its lineup inside the 3-day
  window; Refresh channels is the escape hatch, and `watch()` failures
  already tell the user to refresh. Accepted trade-off, per request.
- **Playback:** none — `create_link` is still called live for every
  watch, so stream URLs are never cached or stale.
- **API 24:** `filesDir`, atomic rename, coroutines `delay` — all fine
  on Android 7.0. No new APIs.
- **Secrets:** no token, MAC, portal URL, or credential is written to
  the cache file in plain form (key is a one-way hash; contents are
  channel/genre data only). Security review is a hard gate on this.

## Always-on rules touched

- Secrets — addressed above; @security-reviewer must pass Step 1's
  storage design before merge.
- User data preservation — additive only, see Risks.
- App identity / ExoPlayer only / no default profiles — untouched.

## Open choice for the user

- TTL: **3 days** (proposed, per "3 days or 5 days") vs 5 days.
  One-line change in the plan; pick at approval.

# Always-On Rules

These rules apply to every change, every persona, every time.

## App identity

- The app is named **777tv**. The fork's application ID is
  `xyz.billybishop4.fleet.tv.dev` — changed from the upstream
  `xyz.billybishop4.fleet.tv` on 2026-11-21 at Karan's explicit
  direction, so fork builds install side-by-side with the upstream app
  on the same TV instead of replacing it. The code namespace stays
  `xyz.billybishop4.fleet.tv`. Do not change the ID again casually;
  builds with this ID update each other in place.
- Production source of truth: the `releases/<version>/` snapshot
  (production.json + SHA256SUMS). A release is not done until its snapshot
  matches the source.

## Playback

- ExoPlayer (Media3) is the only player. There is no engine setting and no
  alternate playback engine may be added.
- Native Android views + RecyclerView. No JavaScript UI.

## User data

- Preserve watch history, add-ons, saved profiles and other app data in any
  test, migration or deployment. Never wipe app data to "fix" a problem.
- Ship **no default IPTV profiles**. Do not restore or provision a profile
  unless the user explicitly asks.
- Leave STBEmu unchanged.

## Secrets — never commit, log, or upload

- Real IPTV/Stalker credentials or portal URLs
- `.tools/stalker-private.json`
- Signing keys and keystores
- `.env` files and private worker-access configuration

Use generated media and Stalker fixtures for tests instead of real accounts.

## Platform floor

- Minimum Android 7.0 / API 24. Do not raise it casually; it is a product
  decision.
- Kotlin only for new code.

## Change discipline

- One feature per branch; commit messages say what and why.
- No drive-by refactors inside a feature change.
- Every behavioral change needs a test or a documented manual check on a TV
  or emulator before it is called done.

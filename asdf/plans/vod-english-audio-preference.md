# Plan: VOD picks non-English audio — prefer English by default

Status: approved by Karan 2026-11-24; implemented and merged (see Outcome)
Requested by: Karan — in the VOD section (movies/TV via add-ons), multi-audio
content often starts on a non-English track; English must be prioritized.

## Root cause (verified in code, 2026-11-24)

- `Preferences.audioLanguage` defaults to `""` (blank).
- `Playback.kt` (NativePlayback.attach) builds the DefaultTrackSelector with
  `setPreferredAudioLanguage(prefs.audioLanguage.takeIf { it.isNotBlank() })`
  → blank becomes **null = no preference**, so ExoPlayer picks by its own
  heuristics (track order/bitrate) — often a non-English track on multi-audio
  releases.
- Settings offers Preferred audio language: `""`, en, es, hi, fr, ja — so the
  mechanism exists; the *default* is the bug. Karan never changed it.

## Fix

1. Add a pure resolver in Playback.kt:
   `internal fun preferredAudioLanguage(prefs: Preferences): String = prefs.audioLanguage.ifBlank { "en" }`
   and use it in attach():
   `setPreferredAudioLanguage(preferredAudioLanguage(prefs))`.
   - Explicit Settings choice (es/hi/fr/ja/en) still wins.
   - Blank (the current default on Karan's install) now means English-first.
   - Applies to the one shared player path, so Live TV gets the same sane
     default; no VOD-only fork.
2. Settings label: the blank option's label function — check how "" is
   displayed; if it reads as "Default/Original", rename that entry's display
   to reflect "English (default)" semantics WITHOUT changing stored values.
   (Display-only change; skip if the label already reads acceptably.)
3. No schema change, no migration: existing saved prefs keep working because
   the resolver interprets blank at playback time.

## Out of scope

- Forcing a mid-playback track switch (selection happens at attach, which is
  when the track is chosen anyway).
- Content with no English-tagged track: selector falls back to the default
  track, unchanged. Tracks tagged "eng"/"en-US" are normalized by Media3.
- Subtitle preferences: untouched.

## Tests

- New pure-JVM unit test for the resolver: blank → "en"; "es" → "es";
  "en" → "en". Runs in the existing kotlinc+JUnit cluster on this VM.
- Full app compile (24 sources) must pass.
- Reviews: @code-reviewer, @security-reviewer (trivial diff; still run).
- Manual-pipeline APK per the verification skill: class parity vs the Gradle
  ground truth (0 missing, R classes present), same persistent keystore
  (f693872b…) so `adb install -r` works over the installed .dev build.
- Deliverable: updated ~/workspace/777tv-0.14.1-dev.apk
  (package xyz.billybishop4.fleet.tv.dev, versionCode 18 / 0.14.1 unchanged).
- TV check is Karan's: play a multi-audio movie/show in VOD that previously
  started non-English; it should start on the English track. The existing
  in-player audio-track switcher remains as the manual override.

## Outcome (2026-11-24)

Implemented on `feature/vod-english-audio` exactly as planned:

- `Playback.kt`: `preferredAudioLanguage(prefs)` resolver added; `attach()`
  passes it to `setPreferredAudioLanguage`. Subtitle lines untouched.
- `SettingsScreen.kt`: the `language` label lambda is shared with the
  subtitle choice, so the audio choice got its own scoped label lambda —
  blank now displays "English (default)" for audio only; the subtitle blank
  still displays "Provider default". Stored values and choice lists
  unchanged.
- `AudioLanguageTest.kt` (new): blank → "en", "es" → "es", "en" → "en" —
  3/3 pass on the pure-JVM cluster. Existing pure tests also pass
  (VideoPicture 5/5, Guide 3/3, Protocol 6/6, Robustness 9/12). The
  remaining failures are all MockWebServer/HTTP tests that fail
  environmentally on this VM (JVM direct TCP denied, HttpFailure -1) —
  same as on pristine main; not a regression.
- Reviews: @code-reviewer approve (no required fixes), @security-reviewer
  pass.
- Full app compile: 24 sources, clean.
- APK: `~/workspace/777tv-0.14.1-dev.apk` rebuilt on the manual pipeline —
  only the app dex changed (manifest/resources byte-identical to the
  previous .dev build). 11,824 defined classes, 0 missing vs the Gradle
  stalker-fix ground truth, `androidx/startup/R$string` defined; the one
  added class is the D8 synthetic for the new Settings label lambda.
  Signed with the persistent keystore (cert f693872b…), so
  `adb install -r` works over the installed .dev build.
  SHA-256 e838fcd43d6c0cc7273a7b42cf5025a9e4919f31b48858e164841ba799e8da8d.
- Still open: Karan's TV check (multi-audio VOD title starts on the
  English track).

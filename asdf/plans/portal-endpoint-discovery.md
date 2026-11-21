# Plan: Stalker portal endpoint discovery

Persona: @planner · Status: approved by user 2026-11-20 ("follow redirects
and figure out the main load.php file path")

## Problem

Providers hand out a bare portal address (e.g. `http://z.zkat.me/`). The
app derives API endpoints only from the entered path, so a bare domain
probes `/server/load.php` and `/portal.php` at the root, gets 404s, and
reports "No Stalker API was found" — even though the portal is healthy
(verified live: `http://z.zkat.me/` 301-redirects to
`/stalker_portal/c/`, and the API answers at
`/stalker_portal/server/load.php`).

## Goal


Entering any of these just works, with only URL + MAC:
- bare origin: `http://host/`
- web-UI address: `http://host/stalker_portal/c/`
- explicit endpoint: `http://host/stalker_portal/server/load.php`

## Design

Discovery runs inside `StalkerClient.Session.authenticate()`, only after
the endpoints derived from the entered URL fail with "not a Stalker API"
(wrong content or 404/405). An endpoint that answers but rejects the
account (ExpiredPortal) is a real endpoint — discovery does not run.

Candidate addresses, in order, deduplicated by endpoint URL:
1. The entered URL's derived address (existing behavior, unchanged).
2. The address derived from the final URL after following the entered
   URL's redirects (HTTP 3xx chain, up to 5, via a new `Http.finalUrl`).
3. Ancestor prefixes of the resolved (or entered) path, longest first —
   e.g. `/stalker_portal/c/` → `/stalker_portal/` → `/` — each deriving
   its own `server/load.php` / `portal.php` endpoints.
4. The well-known `/stalker_portal/` prefix on the entered origin.

Each candidate is verified with a real handshake; the first that returns
a token wins, and the session adopts that candidate's referer/base for
all subsequent calls (profile, channels, guide, links, logos).

## Always-on rules touched

- Secrets: discovery sends only the headers a normal call already sends
  (UA, MAC cookie); cross-origin redirects drop Authorization/Cookie,
  matching `Http`'s existing redirect policy.
- No behavior change for URLs that already work.

## Tests

- MockWebServer: bare root that 302-redirects to `/stalker_portal/c/`
  with the API at `/stalker_portal/server/load.php` → channels load.
- MockWebServer: bare root with no redirect, API only under
  `/stalker_portal/` → probe finds it.
- Unit tests for candidate generation (order + dedupe).
- Existing StalkerTest suite must stay green.

## Follow-ups (not in this change)

- Persist the discovered endpoint on the provider profile so later
  launches skip discovery.
- Ministra end-to-end port (VOD, series, catch-up, radio, account) —
  separate plan.

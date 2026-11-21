# Skill: Release Snapshot

How a version of this fork is cut so source and APK stay verifiable,
following the upstream `releases/` convention.

## Steps

1. Bump version name and version code together.
2. Build the release APK from a clean checkout of `main`.
3. Create `releases/<version>/` with:
   - `production.json` — APK checksum, every production source checksum,
     build configuration checksums, validation scope
   - `SHA256SUMS` — checksums identifying the matching APK
4. Verify the snapshot checksums against the built tree before committing.
5. Confirm exclusions: no signing keys, credentials, device exports,
   imported APKs, or local build outputs in Git.
6. Commit the snapshot as its own commit: `Release <version> snapshot`.

## Rules

- Past snapshots are append-only history; never edit one.
- The application ID does not change. Ever.

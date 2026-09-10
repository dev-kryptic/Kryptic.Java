# Changelog

Notes for each release. The publish workflow copies the matching section into
the GitHub Release.

## Unreleased

## 1.1.1 - 2026-09-10

### Fixed

- Connect to the per-user daemon socket (`~/Library/Application Support/kryptic` on macOS, `$XDG_RUNTIME_DIR` or `~/.config/kryptic` on Linux), not `/tmp/kryptic-daemon.sock`.

## 1.1.0 - 2026-09-10

### Added

- `dev.kryptic:daemon-client-spring-boot` with `@EnableKryptic`, which feeds
  `Kryptic.fetch()` into the Spring Environment as a `kryptic` property source.

## 1.0.1

Release 1.0.1.

## 1.0.0

First production release.

## 0.2.1

- Honor `KRYPTIC_TIMEOUT_MS` on Unix sockets so a silent daemon cannot hang inject.
- Publish pipeline reads Maven Central metadata directly for version resolution.

## 0.2.0

Initial public release.

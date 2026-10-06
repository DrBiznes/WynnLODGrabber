# Changelog

## 1.3.1 — Unreleased
### Added
- **LOD update prompt** — if you already have LODs installed and a newer LOD release is published, you're now asked (once per session) whether to update: **Update LODs**, **Not Right Now**, or **Skip This Version** (you're asked again only for a newer release). `/wynn_lod_update` updates on demand.
- Installs made before LOD versions were tracked are treated as `LOD-04-19-26`, so they get the prompt too.

---

## 1.3.0 — 2026-10-06
### Added
- **Background downloads with a progress toast** — the LOD download no longer spams chat or asks you to stay put; progress, speed and time remaining show in a corner toast while you keep playing. `/wynn_lod_cancel` stops it.
- **Resumable, verified downloads** — interrupted or stalled downloads continue where they left off (even after restarting the game), retry automatically, and are checked against a SHA-256 before anything is installed.
- **`manifest.json` on `master`** — a stable file listing the LOD version, download URLs, sizes and checksums. New LODs can be published by editing it, with no mod update needed, and players who installed LODs are told when a newer release is available.
- **"Install Now" / "Install When I Leave"** — once downloaded you choose when to finish the install. `/wynn_lod_install` does it on demand; leaving the server (or restarting the game) completes a postponed install automatically.
- **New install screen** — replaces the generic "Disconnected" screen with live status, a progress bar, and a **Rejoin Wynncraft** button. After an "Install Now" it rejoins automatically after a 5 second countdown (Esc cancels). If something goes wrong it shows the error and a **Retry** button instead of leaving you waiting.

### Changed
- Each LOD folder is now replaced as a whole when installing, so stale database files (RocksDB `.sst`, SQLite `-wal`/`-shm`) can no longer mix with the new data. The previous folder is restored if the install fails.
- Install waits and retries while Windows still has the LOD mod's files locked.
- The download prompt now shows the real download size and explains the reconnect step.

### Fixed
- The download prompt is no longer re-opened on top of whatever screen you already have open.
- Zip extraction rejects entries that would escape the target folder.
- Free disk space is checked before downloading, and a leftover multi-GB temp file from the old downloader is cleaned up.

---

## 1.2.1 — 2026-04-24
### Added
- **Distant Horizons Fruma LODs** — DH LODs now include the Fruma region. Note: the current file contains fragments of quest areas previously located in the Fruma area due to Wynncraft's map changes. If you have higher-quality Fruma LODs, please contribute via [GitHub Issues](https://github.com/DrBiznes/WynnLODGrabber/issues) or reach out directly.

### Fixed
- **macOS / Apple Silicon crash during installation** — LOD files are now extracted to a staging directory *before* the game disconnects from the server. Previously, writing into Distant Horizons' live data directory while the GPU was still active caused crashes on M-series Macs due to asynchronous Metal command-buffer completion callbacks. Files are now moved into the final location only after DH has fully shut down.

### Changed
- Removed the warning about DH LODs not including Fruma — Fruma is now covered.

---

## 1.2.0 — 2026-04-20
### Added
- **Voxy support** — the mod now detects whether you have Distant Horizons or Voxy installed and offers to download the correct LOD package for your setup
- **Creator & regional IP support** — any `*.wynncraft.com` address (e.g. `olinus.wynncraft.com`) is now recognized and LODs are installed into the correct server folder automatically
- **Conflict detection** — if both Distant Horizons and Voxy are installed simultaneously, a warning screen appears on launch explaining that only one LOD mod can be active at a time
- **5-second disconnect countdown** — chat messages count down before the game disconnects to install LODs, so you know what is happening
- **Updated LODs for Voxy** — includes Fruma

### Changed
- LODs are now extracted directly into the existing IP-specific server folder instead of replacing the entire LOD data directory, so other servers' LOD data is never affected
- Installation and extraction now run on a background thread — the game no longer freezes or hangs during install
- Wynntils is now declared as a required dependency in the mod manifest
- Minimum one LOD mod (Distant Horizons or Voxy) is required to launch

### Fixed
- Screen text (title and description) now renders correctly on all prompt screens
- Mod no longer crashes on launch when Distant Horizons is not installed

### Compatibility
- Minecraft 1.21.11
- Fabric Loader 0.18.4+
- Wynntils 4.1.5+
- Distant Horizons 2.4.5-b-1.21.11 (optional)
- Voxy 0.2.13-alpha (optional)

---

## 1.1.0 — 2025-02-04
### Changed
- Ported to Minecraft 1.21.4

---

## 1.0.3 — 2024-11-23
### Fixed
- Fixed blurry text on the LOD prompt screen
- Fixed "Not Right Now" button not working correctly

---

## 1.0.2 — 2024-11-12
### Changed
- Updated download progress message

---

## 1.0.1 — 2024-11-07
### Fixed
- Minor bug fixes

---

## 1.0.0 — 2024-11-07
- Initial release
- Detects when you join Wynncraft with a character selected and offers to download pre-built Distant Horizons LODs (~1.5 GB)
- Automatically sets Distant Horizons server folder mode to IP-only to keep Wynncraft data separate

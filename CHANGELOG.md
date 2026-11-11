# NuvioTV Ambient Light Sync Changelog

All notable changes to this fork (`a09-netizen/NuvioTV---Hue-Lights`) are documented in this file.
Every release increments `versionCode` and `versionName` in `app/build.gradle.kts` and uses a persistent signing key so you can **update in-place (including via NuvioTV's built-in In-App Updater in Settings → About → Check for updates)** without uninstalling.

---

## [v1.1.2] - 2026-11-11 (`versionCode: 1078`)

### Optimized & Fixed (Playback Startup, Buffering & UI Smoothness)
- **Eliminated Frequent Rebuffering on Fast Internet Connections:**
  - Enabled NuvioTV's **Bitrate-Aware Buffer Engine (`bufferEngineEnabled = true`)**, **Managed Memory Budget (`bufferBudgetManaged = true`)**, **Nuvio Performance Mode (`nuvioPerformanceModeEnabled = true`)**, and **Parallel Multi-Socket Chunk Connections (`parallelNetworkEnabled = true`, `useParallelConnections = true`)** by default. Previously, all three were disabled by default, causing ExoPlayer to fall back to Media3's tiny **13 MB stock buffer cap** (which holds <1.5 seconds of 4K/remux video before stopping network reads).
  - Enforced `prioritizeTimeOverSizeThresholds = true` across all LoadControl paths and raised the fallback buffer target to `100 MB` (`25s` min / `60s` max / `10s` instant seek-back buffer) so high-bitrate 1080p/4K streams continuously buffer ahead.
- **2.5x Faster Playback Startup & Eliminated Mid-Startup Freeze:**
  - Reduced `bufferForPlaybackMs` from `5,000ms` to `2,000ms` and `bufferForPlaybackAfterRebufferMs` to `2,500ms` for near-instant video start and recovery.
  - Disabled `useLibass` by default (`false`) and restored `LibassRenderMode.DEFAULT = OVERLAY` (while keeping `AssSubtitleSurfaceView` guarded away from `TextureView`). Previously, `LibassRenderMode.VIDEO_EFFECT` caused `maybeAdjustLibassPipelineForTracks()` to **destroy and re-initialize ExoPlayer mid-startup** whenever an MKV had an ASS/SSA subtitle track, and forced every 1080p/4K frame through an extra `DefaultVideoFrameProcessor` OpenGL shader pass.
- **Zero-Overhead Ambient Light Sync (`HueOverlayController`):**
  - Added `SurfaceTexture.timestamp` frame-deduplication so `TextureView.getBitmap()` is **completely skipped** when video is paused, seeking, or buffering (zero redundant GPU readbacks).
  - Moved `Bitmap.getPixels()` and 3-zone HSV/perceptual color averaging off the Main UI thread onto the background worker thread, eliminating UI micro-stutters.
  - Stopped re-walking the Activity View hierarchy every 3 seconds once the active video surface is locked.
  - Added a 3-thread parallel Hue zone executor (`hueZoneExecutor`) with a tight `450ms` socket timeout so Left, Center, and Right Hue bulbs update simultaneously without blocking each other.

---

## [v1.1.1] - 2026-11-08 (`versionCode: 1077`)

### Added
- **Govee Local LAN Screen Sync (UDP Ports 4001/4002/4003):**
  - Automatic Wi-Fi discovery for Govee lights with **LAN Control** enabled in the Govee Home app (supports multicast `239.255.255.250:4001`, subnet broadcast, and fast `/24` unicast sweep so Govee lights are found even on routers that block multicast).
  - Real-time low-latency UDP color (`colorwc`) and perceptual brightness (`brightness`) syncing on port `4003`.
  - Per-device Govee screen zone assignment (`LEFT`, `CENTER`, `RIGHT`, `OFF`) alongside Philips Hue bulbs in the on-screen settings menu.
- **Experimental Nanoleaf Local OpenAPI Support (Port 16021):**
  - Added local subnet pairing (`/api/v1/new`) and real-time state syncing (`/api/v1/<token>/state`) for Nanoleaf Shapes, Lines, Canvas, and Light Panels.
- **In-App OTA Updates & Versioned Releases:**
  - Pointed NuvioTV's built-in GitHub Release Updater (`GITHUB_OWNER = "a09-netizen"`, `GITHUB_REPO = "NuvioTV---Hue-Lights"`) to this fork so future incremental releases (`v1.1.2`, `v1.1.3`, etc.) can be installed directly from **Settings → About → Check for updates** without uninstalling.

---

## [v1.1.0] - 2026-11-08 (`versionCode: 1076`)

### Added
- **On-Screen Hue Sync Settings Menu:**
  - Accessible with your TV remote from the **In-Player Control Bar** (`⚙` button next to Sources), the top-right **`Hue Sync` pill** (`UP` twice), or **Settings → Playback / Video**.
  - Live remote adjustments for **Max Brightness (`20%–100%`)**, **Min Brightness (`0%–60%`)**, **Color Saturation (`100%–200%`)**, **Sync Speed (`180ms–500ms`)**, and **Per-Bulb Zone Assignment (`LEFT / CENTER / RIGHT / OFF`)** with live color flash preview.
- **Automatic Philips Hue Bridge Wi-Fi Discovery:**
  - Auto-discovers any tester's Hue Bridge IP via `https://discovery.meethue.com` and a fast local `/24` subnet scan (with `192.168.2.190` as default fallback).

### Fixed
- Fixed blank video screen on main playback when `TextureView` is active by preventing `AssSubtitleSurfaceView` (`ZOrderMediaOverlay`) from punching a transparent hole through the `Z = 0` UI layer and defaulting libass to `VIDEO_EFFECT`.
- Configured production NuvioTV client endpoints so QR Sign-In, TMDB, Trakt, Simkl, IntroDB, and Trailers work out of the box.

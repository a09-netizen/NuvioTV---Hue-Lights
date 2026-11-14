# NuvioTV Ambient Light Sync Changelog

All notable changes to this fork (`a09-netizen/NuvioTV---Hue-Lights`) are documented in this file.
Every release increments `versionCode` and `versionName` in `app/build.gradle.kts` and uses a persistent signing key so you can **update in-place (including via NuvioTV's built-in In-App Updater in Settings → About → Check for updates)** without uninstalling.

---

## [v1.2.0] - 2026-11-14 (`versionCode: 1079`)

### Merged Upstream NuvioTV Updates (`1.2.0-beta.1` / `a3532b819`)
- **Upstream Player & HDR Improvements:**
  - Strip HDR10+ metadata OBUs from AV1 streams (`Av1Hdr10PlusStripper`) to fix AV1 HDR playback glitches.
  - Keep playback position when switching sources (`520fb67cc`) and automatically fall back to the next matching source on stream failure (`c3a27b5e2`).
  - Focus the currently playing stream when the source panel opens (`f4e4d64b3`), stop polling thermal headroom on unsupported devices (`76a5fb20f`), and repaint letterbox bars above the libass overlay (`e30649bc0`).
- **Upstream Continue Watching, Library & Updater Improvements:**
  - Choose what selecting a Continue Watching item opens (`6b6e37edf`), keep series title & metadata stable (`5924f3c95`, `fd0219950`), refresh when upcoming episodes air (`7d4875ea1`), and relaunch cleanly after in-app OTA update installs (`a3532b819`).

### Fixed & Improved (HDR Colors, Persistent Light Preferences & Clean App Exit)
- **Fixed HDR10 / HLG / Dolby Vision Off-Colors (`PlayerScreen.kt` & `HueAutoInitProvider.kt`):**
  - Previously, `ExoPlayerSurface` rendered all streams into a `TextureView` (`trailer_player_view`), which composites video into Android's 8-bit SDR UI layer instead of a hardware overlay plane — causing washed-out SDR clamping on HDR10 (`BT.2020 PQ`) and green/magenta color shifts on Dolby Vision streams.
  - Added automatic **HDR / Dolby Vision detection (`isCurrentlyHdrOrDolbyVision()`)** and a new **`HDR Color & Surface Mode`** setting in the Ambient Light Sync menu (`Auto (Native HDR)` by default, `Native SurfaceView`, or `Force TextureView`). In `Auto (Native HDR)` mode, all HDR10, HDR10+, HLG, and Dolby Vision streams automatically render on a hardware **`SurfaceView`** (`PlayerView(context)`) with 100% accurate native HDR/Dolby Vision TV colors.
- **Fixed Ambient Light Sync Preferences Not Saving Across Restarts (`HueAutoInitProvider.kt` & `NuvioApplication.kt`):**
  - Switched `savePrefs()` from asynchronous `.apply()` to synchronous **`.commit()`** plus an atomic JSON backup file (`filesDir/nuvio_light_sync_prefs.json`) so settings are written to flash storage immediately and never lost when exiting or killing the app.
  - Fixed background startup discovery (`discoverAndMapLights` and `scanGoveeLanLights`) so it **never** overwrites your saved zones, brightness, saturation, speed, or ON/OFF state on launch.
- **Full Background Process Exit on Back Arrow (`MainActivity.kt`):**
  - Fixed `handleExitApp` so exiting the app **always** terminates the background process (`finishAffinity()`, `finishAndRemoveTask()`, `Process.killProcess()`, and `exitProcess(0)`), flushes light sync preferences, and turns off synced lights instead of leaving NuvioTV running in the background.
  - Added a fast 3-click Back-arrow shortcut on any root screen (`Home`, `Search`, `Library`, `Settings`) so pressing Back 3 times quickly exits straight out of the app and closes the background process.

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

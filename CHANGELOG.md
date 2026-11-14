# NuvioTV Ambient Light Sync Changelog

All notable changes to this fork (`a09-netizen/NuvioTV---Hue-Lights`) are documented in this file.
Every release increments `versionCode` and `versionName` in `app/build.gradle.kts` and uses a persistent signing key so you can **update in-place (including via NuvioTV's built-in In-App Updater in Settings → About → Check for updates)** without uninstalling.

---

## [v1.2.1] - 2026-11-14 (`versionCode: 1080`)

### Reverted (Buffering & Network Overrides)
- **Restored Upstream NuvioTV Buffering & Network Defaults (`PlayerSettingsDataStore.kt`, `NuvioExoPlayerPerformanceHelper.kt`, `PlayerRuntimeControllerInitialization.kt`):**
  - Reverted all buffering and network overrides introduced in `v1.1.2` back to exact upstream `1.2.0-beta.1` values (`DEFAULT_MIN_BUFFER_MS = 15,000`, `DEFAULT_MAX_BUFFER_MS = 45,000`, `DEFAULT_BUFFER_FOR_PLAYBACK_MS = 5,000`, `DEFAULT_TARGET_BUFFER_SIZE_MB = 50`, `setPrioritizeTimeOverSizeThresholds(false)` in `NuvioExoPlayerPerformanceHelper`, and `parallelNetworkEnabled = false` / `useParallelConnections = false` by default).
  - **Why `v1.1.2` hurt large 4K / Remux files:** Forcing `setPrioritizeTimeOverSizeThresholds(true)` with a `60s` max buffer caused ExoPlayer to ignore the device's RAM byte budget (`targetBufferBytes`) and attempt to buffer up to `60s` of 60–100 Mbps 4K remux video (`500–750 MB` of RAM), exhausting Android TV heap/arena memory and triggering allocator stalls and GC pauses. Additionally, forcing parallel multi-range HTTP chunk connections (`useParallelConnections = true`) caused many debrid/CDN source servers to throttle or reset connections on large files.

### Audited & Updated Project Dependencies (`gradle/libs.versions.toml` & `app/build.gradle.kts`)
- **Promoted Android TV Compose Libraries from RC to Stable:**
  - `androidx.tv:tv-foundation`: `1.0.0-rc01` → **`1.0.0`**
  - `androidx.tv:tv-material`: `1.1.0-rc01` → **`1.1.0`**
  - `androidx.tvprovider:tvprovider`: `1.0.0` → **`1.1.0`**
- **Updated Core AndroidX & Jetpack Libraries:**
  - `androidx.core:core-ktx`: `1.15.0` → **`1.17.0`**
  - `androidx.core:core-splashscreen`: `1.0.1` → **`1.2.0`**
  - `androidx.appcompat:appcompat`: `1.6.1` → **`1.7.1`**
  - `androidx.lifecycle:lifecycle-runtime-ktx` & `lifecycle-viewmodel-compose`: `2.8.7` → **`2.9.4`**
  - `androidx.activity:activity-compose`: `1.11.0` → **`1.12.4`**
  - `androidx.navigation:navigation-compose`: `2.8.8` → **`2.9.6`**
  - `androidx.datastore:datastore-preferences`: `1.1.1` → **`1.2.1`**
  - `androidx.annotation:annotation-experimental`: `1.3.1` → **`1.5.1`**
  - `androidx.benchmark` / `baselineprofile`: `1.4.1` → **`1.5.0`**
- **Updated Kotlin Ecosystem, DI, Networking & Utilities:**
  - `com.google.devtools.ksp`: `2.3.0` → **`2.3.6`**
  - `androidx.hilt:hilt-navigation-compose`: `1.2.0` → **`1.3.0`** (`hilt-android` kept at `2.58` as `2.59+` requires AGP 9.0+)
  - `org.jetbrains.kotlinx:kotlinx-coroutines`: `1.8.1` → **`1.10.2`**
  - `org.jetbrains.kotlinx:kotlinx-serialization-json`: `1.8.0` → **`1.9.0`**
  - `com.squareup.retrofit2:retrofit` & `converter-moshi`: `2.9.0` → **`2.12.0`**
  - `com.squareup.moshi:moshi-kotlin`: `1.15.1` → **`1.15.2`**
  - `io.coil-kt.coil3:coil-*`: `3.3.0` → **`3.4.0`**
  - `org.jsoup:jsoup`: `1.17.2` → **`1.19.1`**
  - `com.google.code.gson:gson`: `2.10.1` → **`2.13.2`**
  - `com.google.zxing:core`: `3.5.3` → **`3.5.4`**
  - `com.google.guava:guava`: `33.3.1-android` → **`33.8.0-android`**
  - `com.fasterxml.jackson.*`: `2.17.0` → **`2.18.3`**
  - `io.sentry:sentry-android`: `8.47.0` → **`8.64.0`**
  - `com.android.tools:desugar_jdk_libs`: `2.1.4` → **`2.1.5`**
  - *(Note: `media3 = 1.8.0` and `okhttp = 4.12.0` are intentionally kept aligned with Nuvio's prebuilt local engine AARs in `app/libs/` to preserve binary ABI compatibility.)*

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

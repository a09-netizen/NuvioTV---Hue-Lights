# NuvioTV Ambient Light Sync Changelog

All notable changes to this fork (`a09-netizen/NuvioTV---Hue-Lights`) are documented in this file.
Every release increments `versionCode` and `versionName` in `app/build.gradle.kts` and uses a persistent signing key so you can **update in-place (including via NuvioTV's built-in In-App Updater in Settings → About → Check for updates)** without uninstalling.

---

## [v1.2.5] - 2026-11-17 (`versionCode: 1084`)

### Fixed (Prevent Govee H6061 & Hue Bridge Mid-Playback Dropouts)
- **Fixed Govee H6061 (`192.168.2.47`) Stopping After ~60 Seconds (`HueAutoInitProvider.kt`):**
  - Previously, `sendGoveeState()` sent two UDP packets (`colorwc` and `brightness`) back-to-back with `0ms` spacing on port `4003` whenever scene brightness changed by `>= 4%`. On Govee H6061 Glide Hexa Panels, sending `cmd: "brightness"` repeatedly back-to-back with `colorwc` overflows the Wi-Fi MCU's single-packet UDP buffer and causes the Govee LAN listener to stop responding after ~60 seconds.
  - Now `sendGoveeState()` sends `turn: 1` + `brightness: 100` **only once** at startup, encodes dynamic frame brightness (`minBriPercent..maxBriPercent`) directly into the RGB values `(r, g, b)` of a **single `colorwc` UDP packet**, and enforces a `240ms` per-device rate limit so the Govee H6061 never drops out.
- **Switched Philips Hue Bridge v1 REST Calls to Fast Local `http` First + Per-Bulb Pacing (`HueAutoInitProvider.kt`):**
  - Defaults `activeScheme` to plain local `http` (`http://192.168.2.190/api/...`, with automatic `https` fallback) to eliminate TLS handshake exhaustion after 900+ requests on the Hue Bridge (`BSB002`), and enforces a `320ms` minimum interval per bulb so the Bridge's 10 commands/sec Zigbee queue is never overwhelmed.
- **Added `isSendingFrame` Watchdog & Periodic Re-Sync During Stream Buffering (`HueAutoInitProvider.kt`):**
  - Added a `2,500ms` watchdog on `isSendingFrame` and periodic view hierarchy rescan (`findActiveVideoTextureView` top-down) plus a `2,000ms` keep-alive capture even when `SurfaceTexture.timestamp` pauses during P2P stream buffering (`STATE_BUFFERING`).

---

## [v1.2.4] - 2026-11-17 (`versionCode: 1083`)

### Fixed (Restored Real-Time Light Sync Capture + Remote Interaction Cooldown)
- **Restored `TextureView` Capture When Light Sync Is ON (`HueAutoInitProvider.kt`):**
  - The `v1.2.3` log from `http://192.168.2.126:8989/` (`ReadbackBypass=true, lastReadback=16ms`) confirmed that on Google Chromecast (`sabrina`), Amlogic's hardware `SurfaceView` overlay plane blocks `PixelCopy.request()` (`ERROR_SOURCE_INVALID`), so real-time screen colors can **only** be captured when `ExoPlayerSurface` uses `TextureView`.
  - Restored `shouldUseTextureView()` to return `true` by default (`Sync All (TextureView)`) whenever **Ambient Screen Sync is ON** (and `false` whenever Sync is OFF, or on HDR streams if the user selects `Bypass HDR (Native HDR)` in the menu), and removed the `readbackBypassedForCurrentView` cutoff on `TextureView` so `videoView.getBitmap(sampleBitmap)` continuously captures real video colors.
- **Eliminated Remote Input Lag During `TextureView` Light Sync (`HueAutoInitProvider.kt`):**
  - Added a **`1,500ms` Remote Interaction Cooldown (`lastUserKeyTimeMs`)**: the instant you press any button on your remote (opening Player Controls, seeking, switching sources, opening settings, or pressing Back), `getBitmap()` pauses for `1.5 seconds` so the UI and remote respond at 60fps with zero contention, and resumes automatically once you stop pressing keys.
  - Added adaptive sample pacing (`max(currentSampleIntervalMs(), lastReadbackDurationMs * 2)`).
- **Fixed Hue Bridge TLS Handshake Timeouts & Added Live Sync Telemetry (`HueAutoInitProvider.kt`):**
  - Raised `hueHttpClient` timeouts from `450ms` to `1,200ms` with a 5-minute keep-alive connection pool and automatic `https` ↔ `http` scheme fallback so parallel 3-zone Hue Bridge updates never time out during TLS handshakes.
  - Added live Light Sync telemetry (`captured` frame count, `lastSampledRgb` for Left/Center/Right, `hueOk`/`hueErr`, and active surface class) to `http://<tv-ip>:8989/`.

---

## [v1.2.3] - 2026-11-17 (`versionCode: 1082`)

### Fixed (Diagnosed via Live Chromecast Log `http://192.168.2.126:8989/`)
- **Eliminated 1.4s–2.5s Main Thread / `RenderThread` Freezes on Google Chromecast Android 14 (`HueAutoInitProvider.kt`):**
  - Live logs from `http://192.168.2.126:8989/` revealed that on Google Chromecast with Google TV (`sabrina`, Android 14 SDK 34), rendering SDR video onto a `TextureView` and calling `TextureView.getBitmap()` on the Main Thread triggered an Android 14 SELinux `dmabuf` denial (`W/RenderThread: avc: denied { getattr } for name="/" dev="dmabuf"`) and stalled `OpenGLRenderer` / Main Thread for **1,300ms to 2,539ms per sample** (`Davey! duration=2411ms`, `Skipped 128 frames!`, `CCodecBufferChannel: no present fence`).
  - **Fix 1 (Always Use Native `SurfaceView` by Default):** `shouldUseTextureView()` now returns `false` by default for **both SDR and HDR/Dolby Vision** streams so `c2.amlogic.hevc.decoder` always outputs directly to the hardware composer (`SurfaceView`), completely bypassing `RenderThread` `TextureView` composition.
  - **Fix 2 (`PixelCopy` Latency Circuit Breaker):** Added a 90ms latency monitor on `PixelCopy.request()`. If the device's GPU/SELinux blocks or stalls `dmabuf` readback (`> 90ms` or non-success twice), `HueOverlayController` automatically trips a circuit breaker (`readbackBypassedForCurrentView = true`) to stop all GPU readbacks for that stream and switches to a zero-overhead background ambient synthesizer (`0.0ms` Main Thread / `RenderThread` impact).
  - **Fix 3 (HTTP Log Server Request Drain):** Drains incoming HTTP request headers on port `8989` before closing the socket so `http://<tv-ip>:8989/` never resets the TCP connection on browsers or HTTP clients.

---

## [v1.2.2] - 2026-11-17 (`versionCode: 1081`)

### Fixed (Unexpected App Shutdowns & Crashes)
- **Restored Binary-Compatible Upstream Dependency Versions (`gradle/libs.versions.toml` & `app/build.gradle.kts`):**
  - Reverted the `v1.2.1` major/minor upgrades of `navigation-compose` (`2.9.6` → `2.8.8`), `lifecycle` (`2.9.4` → `2.8.7`), `activity-compose` (`1.12.4` → `1.11.0`), `datastore` (`1.2.1` → `1.1.1`), `retrofit` (`2.12.0` → `2.9.0`), `coroutines` (`1.10.2` → `1.8.1`), and `tv-material` (`1.1.0` → `1.1.0-rc01`). Those KMP-rewritten versions changed internal bytecode method signatures (`6,908` changed startup methods in D8) and conflicted with `NavSessionViewModelStore.kt`, `compose-bom:2026.05.01`, `supabase:3.2.1`, and Nuvio's prebuilt AARs at runtime.
- **Removed Pre-Emptive 3-Press Back Interceptor (`MainActivity.kt`):**
  - Removed the `dispatchKeyEvent` 3-Back-press counter that was killing the app when pressing Back rapidly inside Settings sub-menus, dialogs, or Home row scrolling while on a root route. Exiting via Back now follows NuvioTV's normal Compose `BackHandler` flow (Back opens Sidebar → Back calls `exitApplicationCompletely()` to cleanly exit and terminate the background process).
- **Safe Early HDR Detection & Surface Binding (`PlayerScreen.kt`):**
  - Added early stream metadata HDR/Dolby Vision detection (`HDR_STREAM_HINT_REGEX` on `currentStreamName` / `currentStreamDescription` / `currentStreamUrl`) so HDR streams start directly on a hardware `SurfaceView` at `t = 0ms` without hot-swapping from `TextureView` mid-decode, and moved `playerView.player = player` strictly inside `DisposableEffect`.

### Added (Built-In Crash & Diagnostic Log Viewer + Wi-Fi Web Log Server)
- **Global Uncaught Crash Recorder (`HueAutoInitProvider.kt`):**
  - Automatically captures any uncaught exception on any thread (`Thread.setDefaultUncaughtExceptionHandler`) along with device info, full stack trace, and the last 90 lines of `logcat` into persistent storage (`filesDir/nuvio_crash_logs.txt` and `nuvio_crash_store`).
- **On-Screen TV Crash & Diagnostic Log Viewer + Wi-Fi Browser Endpoint (`http://<tv-ip>:8989`):**
  - Open **Settings → Playback / Video → Crash & Diagnostic Logs** (or open the **Ambient Light Sync** menu and select **Crash & Diagnostic Logs**) to read saved crash stack traces and live `logcat` directly on your TV screen (`UP`/`DOWN` to scroll, `OK` to refresh, `LEFT`/`RIGHT` to clear crash history), or open **`http://<tv-ip>:8989`** on any phone or PC browser on the same Wi-Fi.

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

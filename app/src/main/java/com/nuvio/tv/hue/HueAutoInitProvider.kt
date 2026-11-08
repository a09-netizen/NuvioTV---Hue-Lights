package com.nuvio.tv.hue

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

class HueAutoInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return false
        HueOverlayController.init(app)
        return true
    }

    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

object HueOverlayController : Application.ActivityLifecycleCallbacks {
    private const val DEFAULT_BRIDGE_IP = "192.168.2.190"
    private const val SCAN_INTERVAL_MS = 1000L
    private const val SAMPLE_W = 16
    private const val SAMPLE_H = 9

    private lateinit var appContext: Context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val copyThread = HandlerThread("NuvioHuePixelCopy").apply { start() }
    private val copyHandler = Handler(copyThread.looper)
    private val sampleBitmap = Bitmap.createBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.ARGB_8888)
    private val pixelBuffer = IntArray(SAMPLE_W * SAMPLE_H)
    private val isSendingFrame = AtomicBoolean(false)
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val hueHttpClient: OkHttpClient by lazy {
        val trustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .connectTimeout(700, TimeUnit.MILLISECONDS)
            .readTimeout(700, TimeUnit.MILLISECONDS)
            .writeTimeout(700, TimeUnit.MILLISECONDS)
            .connectionPool(ConnectionPool(4, 30, TimeUnit.SECONDS))
            .retryOnConnectionFailure(false)
            .build()
    }

    private data class LastLightState(
        val x: Float,
        val y: Float,
        val bri: Int,
        val timeMs: Long
    )

    private val lastLightStates = ConcurrentHashMap<String, LastLightState>()

    @Volatile
    private var bridgeIp: String = DEFAULT_BRIDGE_IP
    @Volatile
    private var activeScheme: String = "https"
    private var hueUsername: String = ""
    private var isSyncEnabled: Boolean = false
    private var leftLights = listOf<String>()
    private var rightLights = listOf<String>()
    private var centerLights = listOf<String>()
    private val allLightsMap = LinkedHashMap<String, String>() // lightId -> lightName
    private var customZonesEnabled: Boolean = false

    // User-adjustable settings
    @Volatile
    private var maxBriPercent: Int = 100 // 20..100 (step 5)
    @Volatile
    private var minBriPercent: Int = 30  // 0..60 (step 5)
    @Volatile
    private var saturationPercent: Int = 140 // 100..200 (step 10)
    @Volatile
    private var speedPreset: Int = 1 // 0=Relaxed(500ms), 1=Balanced(350ms), 2=Fast(250ms), 3=Ultra(180ms)

    private var currentActivity: Activity? = null
    private var pillView: TextView? = null
    private var settingsContainer: FrameLayout? = null
    private var settingsScrollView: ScrollView? = null
    private var settingsRowsLayout: LinearLayout? = null
    private var settingsStatusText: TextView? = null
    private var isSettingsOpen: Boolean = false
    private var selectedRowIndex: Int = 0
    private val settingsRowActions = mutableListOf<SettingsRowItem>()

    private var activeVideoView: View? = null
    private var loopRunning = false
    private var lastUpPressTimeMs = 0L
    private var lastSurfaceScanTimeMs = 0L

    private data class SettingsRowItem(
        val title: String,
        val subtitle: String?,
        val valueProvider: () -> String,
        val onLeft: () -> Unit,
        val onRight: () -> Unit,
        val onCenter: () -> Unit,
        var rowView: LinearLayout? = null,
        var valueView: TextView? = null
    )

    private val hidePillRunnable = Runnable {
        if (isSettingsOpen) return@Runnable
        pillView?.clearFocus()
        pillView?.animate()?.alpha(0f)?.setDuration(400)?.withEndAction {
            pillView?.visibility = View.GONE
        }?.start()
    }

    fun init(app: Application) {
        appContext = app
        val prefs = app.getSharedPreferences("nuvio_hue_prefs", Context.MODE_PRIVATE)
        bridgeIp = prefs.getString("hue_bridge_ip", DEFAULT_BRIDGE_IP)?.takeIf { it.isNotBlank() } ?: DEFAULT_BRIDGE_IP
        hueUsername = prefs.getString("hue_username", "") ?: ""
        isSyncEnabled = prefs.getBoolean("hue_sync_enabled", false)
        maxBriPercent = prefs.getInt("hue_max_bri_pct", 100).coerceIn(20, 100)
        minBriPercent = prefs.getInt("hue_min_bri_pct", 30).coerceIn(0, 60)
        saturationPercent = prefs.getInt("hue_sat_pct", 140).coerceIn(100, 200)
        speedPreset = prefs.getInt("hue_speed_preset", 1).coerceIn(0, 3)
        customZonesEnabled = prefs.getBoolean("hue_custom_zones", false)
        loadSavedZones()
        ioExecutor.execute {
            if (!isBridgeReachable(bridgeIp)) {
                discoverHueBridgeIp()?.let { foundIp ->
                    bridgeIp = foundIp
                    savePrefs()
                    updatePillUI()
                }
            }
            if (hueUsername.isNotEmpty()) {
                discoverAndMapLights(forceAutoMap = false)
            }
        }
        app.registerActivityLifecycleCallbacks(this)
    }

    private fun currentSampleIntervalMs(): Long = when (speedPreset) {
        0 -> 500L
        1 -> 350L
        2 -> 250L
        3 -> 180L
        else -> 350L
    }

    private fun currentTransitionTime(): Int = when (speedPreset) {
        0 -> 6
        1 -> 4
        2 -> 3
        3 -> 2
        else -> 4
    }

    private fun speedPresetLabel(): String = when (speedPreset) {
        0 -> "Relaxed (500ms)"
        1 -> "Balanced (350ms)"
        2 -> "Fast (250ms)"
        3 -> "Ultra Fast (180ms)"
        else -> "Balanced (350ms)"
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
        attachOverlayToActivity(activity)
        startSurfaceScanner()
    }

    override fun onActivityPaused(activity: Activity) {
        if (currentActivity === activity) {
            hideSettingsPanel()
            loopRunning = false
            activeVideoView = null
            currentActivity = null
            pillView = null
            settingsContainer = null
            settingsScrollView = null
            settingsRowsLayout = null
            settingsStatusText = null
        }
    }

    private fun attachOverlayToActivity(activity: Activity) {
        val root = activity.window?.decorView as? ViewGroup ?: return
        val existing = root.findViewWithTag<TextView>("NUVIO_HUE_PILL")
        if (existing != null) {
            pillView = existing
            settingsContainer = root.findViewWithTag("NUVIO_HUE_SETTINGS")
            updatePillUI()
        } else {
            val pill = TextView(activity).apply {
                tag = "NUVIO_HUE_PILL"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(18), dp(9), dp(18), dp(9))
                isFocusable = true
                isFocusableInTouchMode = false
                visibility = View.GONE
                elevation = 50f
                setOnFocusChangeListener { _, hasFocus ->
                    updatePillBackground(this, hasFocus)
                    if (hasFocus) showPillTemporarily()
                }
                setOnClickListener {
                    showSettingsPanel()
                }
            }

            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(24)
                marginEnd = dp(32)
            }

            root.addView(pill, params)
            pillView = pill
            updatePillUI()
        }

        val origCallback = activity.window.callback
        if (origCallback != null && origCallback !is HueWindowCallback) {
            activity.window.callback = HueWindowCallback(origCallback)
        }
    }

    private class HueWindowCallback(val base: Window.Callback) : Window.Callback by base {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (isSettingsOpen) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            moveSettingsSelection(-1)
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            moveSettingsSelection(1)
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            settingsRowActions.getOrNull(selectedRowIndex)?.onLeft?.invoke()
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            settingsRowActions.getOrNull(selectedRowIndex)?.onRight?.invoke()
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (event.repeatCount == 0) {
                                settingsRowActions.getOrNull(selectedRowIndex)?.onCenter?.invoke()
                            }
                            return true
                        }
                        KeyEvent.KEYCODE_BACK,
                        KeyEvent.KEYCODE_ESCAPE -> {
                            hideSettingsPanel()
                            return true
                        }
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER,
                        KeyEvent.KEYCODE_BACK,
                        KeyEvent.KEYCODE_ESCAPE -> return true
                    }
                }
                return true
            }

            if (activeVideoView != null) {
                val pill = pillView
                if (pill != null && pill.visibility == View.VISIBLE && pill.hasFocus()) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                                showSettingsPanel()
                            }
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_BACK -> {
                            if (event.action == KeyEvent.ACTION_DOWN) {
                                pill.clearFocus()
                                updatePillBackground(pill, false)
                            }
                            return true
                        }
                    }
                }
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val wasVisible = pill?.visibility == View.VISIBLE
                    showPillTemporarily()
                    if (event.keyCode == KeyEvent.KEYCODE_PROG_YELLOW ||
                        event.keyCode == KeyEvent.KEYCODE_PROG_BLUE ||
                        event.keyCode == KeyEvent.KEYCODE_BOOKMARK ||
                        event.keyCode == KeyEvent.KEYCODE_CAPTIONS
                    ) {
                        showSettingsPanel()
                        return true
                    }
                    if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        val now = System.currentTimeMillis()
                        if (wasVisible && now - lastUpPressTimeMs < 1500L && pill != null) {
                            pill.requestFocus()
                            updatePillBackground(pill, true)
                            return true
                        }
                        lastUpPressTimeMs = now
                    } else {
                        lastUpPressTimeMs = 0L
                    }
                }
            }
            return base.dispatchKeyEvent(event)
        }
    }

    @JvmStatic
    fun showSettingsPanel() {
        mainHandler.post {
            val activity = currentActivity ?: return@post
            val root = activity.window?.decorView as? ViewGroup ?: return@post

            var container = root.findViewWithTag<FrameLayout>("NUVIO_HUE_SETTINGS")
            if (container == null) {
                container = buildSettingsContainer(activity)
                root.addView(
                    container,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                settingsContainer = container
            }

            isSettingsOpen = true
            pillView?.clearFocus()
            pillView?.visibility = View.GONE
            container.visibility = View.VISIBLE
            container.bringToFront()

            if (hueUsername.isNotEmpty() && allLightsMap.isEmpty()) {
                ioExecutor.execute {
                    discoverAndMapLights(forceAutoMap = false)
                    mainHandler.post {
                        if (isSettingsOpen) rebuildSettingsRows(keepSelection = true)
                    }
                }
            }
            rebuildSettingsRows(keepSelection = false)
        }
    }

    @JvmStatic
    fun hideSettingsPanel() {
        mainHandler.post {
            isSettingsOpen = false
            settingsContainer?.visibility = View.GONE
            if (activeVideoView != null) {
                showPillTemporarily()
            }
        }
    }

    private fun buildSettingsContainer(activity: Activity): FrameLayout {
        val backdrop = FrameLayout(activity).apply {
            tag = "NUVIO_HUE_SETTINGS"
            setBackgroundColor(Color.parseColor("#88000000"))
            elevation = 100f
            isClickable = true
            isFocusable = true
            setOnClickListener { hideSettingsPanel() }
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(22))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#F214141C"))
                setStroke(dp(1), Color.parseColor("#44FFFFFF"))
            }
            isClickable = true
        }

        val cardParams = FrameLayout.LayoutParams(
            dp(430),
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.END or Gravity.CENTER_VERTICAL
        ).apply {
            topMargin = dp(20)
            bottomMargin = dp(20)
            marginEnd = dp(24)
        }

        val headerTitle = TextView(activity).apply {
            text = "Philips Hue Screen Sync"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.DEFAULT_BOLD
        }

        val headerSub = TextView(activity).apply {
            text = "Use UP/DOWN to navigate • LEFT/RIGHT to adjust • BACK to close"
            setTextColor(Color.parseColor("#B0B0C0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(0, dp(4), 0, dp(10))
        }

        val statusView = TextView(activity).apply {
            setTextColor(Color.parseColor("#FFB74D"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
            visibility = View.GONE
        }
        settingsStatusText = statusView

        val divider = View(activity).apply {
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                bottomMargin = dp(10)
            }
        }

        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        settingsScrollView = scroll

        val rowsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        settingsRowsLayout = rowsContainer
        scroll.addView(rowsContainer)

        card.addView(headerTitle)
        card.addView(headerSub)
        card.addView(statusView)
        card.addView(divider)
        card.addView(scroll)

        backdrop.addView(card, cardParams)
        return backdrop
    }

    private fun setStatusBanner(message: String?) {
        mainHandler.post {
            val view = settingsStatusText ?: return@post
            if (message.isNullOrBlank()) {
                view.visibility = View.GONE
            } else {
                view.text = message
                view.visibility = View.VISIBLE
            }
        }
    }

    private fun rebuildSettingsRows(keepSelection: Boolean) {
        val activity = currentActivity ?: return
        val rowsLayout = settingsRowsLayout ?: return
        val prevSelection = if (keepSelection) selectedRowIndex else 0

        rowsLayout.removeAllViews()
        settingsRowActions.clear()

        // 1. Hue Sync ON / OFF
        val toggleSync = {
            if (hueUsername.isEmpty()) {
                triggerBridgePairing()
            } else {
                isSyncEnabled = !isSyncEnabled
                savePrefs()
                if (isSyncEnabled) {
                    lastLightStates.clear()
                    previewCurrentBrightnessOnLights()
                } else {
                    turnOffSyncedLights()
                }
                updatePillUI()
                refreshAllRowValues()
            }
        }
        settingsRowActions.add(
            SettingsRowItem(
                title = "Hue Screen Sync",
                subtitle = if (hueUsername.isEmpty()) "Pair with Hue Bridge ($bridgeIp) first" else "Sync room lights to live video colors",
                valueProvider = {
                    when {
                        hueUsername.isEmpty() -> "UNPAIRED"
                        isSyncEnabled -> "ON"
                        else -> "OFF"
                    }
                },
                onLeft = toggleSync,
                onRight = toggleSync,
                onCenter = toggleSync
            )
        )

        // 2. Max Brightness
        settingsRowActions.add(
            SettingsRowItem(
                title = "Max Brightness",
                subtitle = "Peak bulb brightness during bright video scenes",
                valueProvider = { "◀  $maxBriPercent%  ▶" },
                onLeft = {
                    maxBriPercent = (maxBriPercent - 5).coerceAtLeast(max(20, minBriPercent + 5))
                    savePrefs()
                    lastLightStates.clear()
                    previewCurrentBrightnessOnLights()
                    refreshAllRowValues()
                },
                onRight = {
                    maxBriPercent = (maxBriPercent + 5).coerceAtMost(100)
                    savePrefs()
                    lastLightStates.clear()
                    previewCurrentBrightnessOnLights()
                    refreshAllRowValues()
                },
                onCenter = {
                    maxBriPercent = if (maxBriPercent >= 100) 50 else (maxBriPercent + 10).coerceAtMost(100)
                    savePrefs()
                    lastLightStates.clear()
                    previewCurrentBrightnessOnLights()
                    refreshAllRowValues()
                }
            )
        )

        // 3. Min Brightness (Dark Scenes)
        settingsRowActions.add(
            SettingsRowItem(
                title = "Min Brightness (Dark Scenes)",
                subtitle = "Floor brightness so dark scenes stay visible (0% = full dim)",
                valueProvider = { "◀  $minBriPercent%  ▶" },
                onLeft = {
                    minBriPercent = (minBriPercent - 5).coerceAtLeast(0)
                    savePrefs()
                    lastLightStates.clear()
                    previewCurrentBrightnessOnLights()
                    refreshAllRowValues()
                },
                onRight = {
                    minBriPercent = (minBriPercent + 5).coerceAtMost(min(60, maxBriPercent - 5))
                    savePrefs()
                    lastLightStates.clear()
                    previewCurrentBrightnessOnLights()
                    refreshAllRowValues()
                },
                onCenter = {
                    minBriPercent = if (minBriPercent >= 50) 0 else (minBriPercent + 10).coerceAtMost(min(60, maxBriPercent - 5))
                    savePrefs()
                    lastLightStates.clear()
                    previewCurrentBrightnessOnLights()
                    refreshAllRowValues()
                }
            )
        )

        // 4. Color Saturation Boost
        settingsRowActions.add(
            SettingsRowItem(
                title = "Color Saturation",
                subtitle = "Boost color vibrancy on Hue bulbs",
                valueProvider = { "◀  $saturationPercent%  ▶" },
                onLeft = {
                    saturationPercent = (saturationPercent - 10).coerceAtLeast(100)
                    savePrefs()
                    lastLightStates.clear()
                    refreshAllRowValues()
                },
                onRight = {
                    saturationPercent = (saturationPercent + 10).coerceAtMost(200)
                    savePrefs()
                    lastLightStates.clear()
                    refreshAllRowValues()
                },
                onCenter = {
                    saturationPercent = if (saturationPercent >= 200) 100 else (saturationPercent + 20).coerceAtMost(200)
                    savePrefs()
                    lastLightStates.clear()
                    refreshAllRowValues()
                }
            )
        )

        // 5. Sync Speed / Smoothness
        settingsRowActions.add(
            SettingsRowItem(
                title = "Sync Speed",
                subtitle = "How fast lights react to screen changes",
                valueProvider = { "◀  ${speedPresetLabel()}  ▶" },
                onLeft = {
                    speedPreset = (speedPreset + 3) % 4
                    savePrefs()
                    refreshAllRowValues()
                },
                onRight = {
                    speedPreset = (speedPreset + 1) % 4
                    savePrefs()
                    refreshAllRowValues()
                },
                onCenter = {
                    speedPreset = (speedPreset + 1) % 4
                    savePrefs()
                    refreshAllRowValues()
                }
            )
        )

        // 6. Per-Light Zone Assignment Rows
        if (allLightsMap.isNotEmpty()) {
            for ((lightId, lightName) in allLightsMap) {
                val cycleZone = { delta: Int ->
                    val zones = listOf("LEFT", "CENTER", "RIGHT", "OFF")
                    val curr = getLightZone(lightId)
                    val idx = zones.indexOf(curr).let { if (it < 0) 3 else it }
                    val nextZone = zones[(idx + delta + zones.size) % zones.size]
                    setLightZone(lightId, nextZone)
                    flashSingleLightPreview(lightId, nextZone)
                    updatePillUI()
                    refreshAllRowValues()
                }
                settingsRowActions.add(
                    SettingsRowItem(
                        title = "💡 $lightName (ID $lightId)",
                        subtitle = "Screen zone assignment for this light",
                        valueProvider = { "◀  ${getLightZone(lightId)}  ▶" },
                        onLeft = { cycleZone(-1) },
                        onRight = { cycleZone(1) },
                        onCenter = { cycleZone(1) }
                    )
                )
            }
        }

        // 7. Test Light Zones
        val runZoneTest = {
            setStatusBanner("Flashing zones: Left = Red, Center = Green, Right = Blue...")
            ioExecutor.execute {
                testLightZonesPattern()
                mainHandler.postDelayed({ setStatusBanner(null) }, 2200L)
            }
        }
        settingsRowActions.add(
            SettingsRowItem(
                title = "Test Light Zones",
                subtitle = "Flash Red (Left), Green (Center), Blue (Right) on bulbs",
                valueProvider = { "TEST" },
                onLeft = runZoneTest,
                onRight = runZoneTest,
                onCenter = runZoneTest
            )
        )

        // 8. Pair / Re-scan Bridge
        val pairOrRescan = {
            triggerBridgePairing()
        }
        val autoDiscoverBridge = {
            setStatusBanner("Scanning Wi-Fi network for Philips Hue Bridge...")
            ioExecutor.execute {
                val found = discoverHueBridgeIp()
                if (found != null) {
                    if (found != bridgeIp) {
                        bridgeIp = found
                        hueUsername = ""
                        savePrefs()
                    }
                    setStatusBanner("Found Hue Bridge at $bridgeIp! Press OK to Pair/Rescan.")
                    updatePillUI()
                    mainHandler.post { if (isSettingsOpen) rebuildSettingsRows(keepSelection = true) }
                } else {
                    setStatusBanner("Could not auto-discover another bridge; keeping $bridgeIp.")
                }
            }
        }
        settingsRowActions.add(
            SettingsRowItem(
                title = if (hueUsername.isEmpty()) "Pair Hue Bridge ($bridgeIp)" else "Hue Bridge ($bridgeIp)",
                subtitle = if (hueUsername.isEmpty()) "Press Bridge link button then OK • LEFT/RIGHT to auto-discover IP" else "Press OK to rescan lights • LEFT/RIGHT to auto-discover Bridge IP",
                valueProvider = { if (hueUsername.isEmpty()) "PAIR" else "RESCAN" },
                onLeft = autoDiscoverBridge,
                onRight = autoDiscoverBridge,
                onCenter = pairOrRescan
            )
        )

        // 9. Reset to Defaults
        val resetDefaults = {
            maxBriPercent = 100
            minBriPercent = 30
            saturationPercent = 140
            speedPreset = 1
            customZonesEnabled = false
            savePrefs()
            lastLightStates.clear()
            if (hueUsername.isNotEmpty()) {
                ioExecutor.execute {
                    discoverAndMapLights(forceAutoMap = true)
                    mainHandler.post {
                        setStatusBanner("Reset to defaults & auto-mapped light zones")
                        if (isSettingsOpen) rebuildSettingsRows(keepSelection = true)
                    }
                }
            } else {
                refreshAllRowValues()
            }
        }
        settingsRowActions.add(
            SettingsRowItem(
                title = "Reset Hue Defaults",
                subtitle = "Restore default brightness, saturation, speed & auto-zones",
                valueProvider = { "RESET" },
                onLeft = resetDefaults,
                onRight = resetDefaults,
                onCenter = resetDefaults
            )
        )

        // 10. Close Menu
        settingsRowActions.add(
            SettingsRowItem(
                title = "Close Hue Settings",
                subtitle = "Return to playback (or press BACK on remote)",
                valueProvider = { "DONE" },
                onLeft = { hideSettingsPanel() },
                onRight = { hideSettingsPanel() },
                onCenter = { hideSettingsPanel() }
            )
        )

        settingsRowActions.forEachIndexed { index, item ->
            val row = createRowView(activity, index, item)
            rowsLayout.addView(row)
        }

        selectedRowIndex = prevSelection.coerceIn(0, max(0, settingsRowActions.size - 1))
        updateRowSelectionStyles()
    }

    private fun createRowView(activity: Activity, index: Int, item: SettingsRowItem): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
            setOnClickListener {
                selectedRowIndex = index
                updateRowSelectionStyles()
                item.onCenter.invoke()
            }
        }

        val textCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10)
            }
        }

        val titleView = TextView(activity).apply {
            text = item.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        textCol.addView(titleView)

        if (!item.subtitle.isNullOrBlank()) {
            val subView = TextView(activity).apply {
                text = item.subtitle
                setTextColor(Color.parseColor("#9E9EAE"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            textCol.addView(subView)
        }

        val valView = TextView(activity).apply {
            text = item.valueProvider()
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#33FFFFFF"))
            }
        }

        row.addView(textCol)
        row.addView(valView)

        item.rowView = row
        item.valueView = valView
        return row
    }

    private fun moveSettingsSelection(delta: Int) {
        if (settingsRowActions.isEmpty()) return
        selectedRowIndex = (selectedRowIndex + delta + settingsRowActions.size) % settingsRowActions.size
        updateRowSelectionStyles()
    }

    private fun updateRowSelectionStyles() {
        settingsRowActions.forEachIndexed { idx, item ->
            val row = item.rowView ?: return@forEachIndexed
            val selected = idx == selectedRowIndex
            row.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                if (selected) {
                    colors = intArrayOf(
                        Color.parseColor("#557B1FA2"),
                        Color.parseColor("#55E91E63")
                    )
                    orientation = GradientDrawable.Orientation.LEFT_RIGHT
                    setStroke(dp(2), Color.WHITE)
                } else {
                    setColor(Color.parseColor("#1FFFFFFF"))
                    setStroke(dp(1), Color.parseColor("#22FFFFFF"))
                }
            }
            item.valueView?.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                if (selected) {
                    setColor(Color.parseColor("#FF9800"))
                } else {
                    setColor(Color.parseColor("#2AFFFFFF"))
                }
            }
            item.valueView?.setTextColor(if (selected) Color.BLACK else Color.WHITE)
            if (selected) {
                settingsScrollView?.post {
                    settingsScrollView?.smoothScrollTo(0, max(0, row.top - dp(80)))
                }
            }
        }
    }

    private fun refreshAllRowValues() {
        mainHandler.post {
            settingsRowActions.forEach { item ->
                item.valueView?.text = item.valueProvider()
            }
            updateRowSelectionStyles()
        }
    }

    private fun getLightZone(lightId: String): String = when {
        leftLights.contains(lightId) -> "LEFT"
        centerLights.contains(lightId) -> "CENTER"
        rightLights.contains(lightId) -> "RIGHT"
        else -> "OFF"
    }

    private fun setLightZone(lightId: String, zone: String) {
        val l = leftLights.toMutableList().apply { remove(lightId) }
        val c = centerLights.toMutableList().apply { remove(lightId) }
        val r = rightLights.toMutableList().apply { remove(lightId) }
        when (zone) {
            "LEFT" -> l.add(lightId)
            "CENTER" -> c.add(lightId)
            "RIGHT" -> r.add(lightId)
        }
        leftLights = l
        centerLights = c
        rightLights = r
        customZonesEnabled = true
        savePrefs()
    }

    private fun savePrefs() {
        appContext.getSharedPreferences("nuvio_hue_prefs", Context.MODE_PRIVATE).edit()
            .putString("hue_bridge_ip", bridgeIp)
            .putBoolean("hue_sync_enabled", isSyncEnabled)
            .putInt("hue_max_bri_pct", maxBriPercent)
            .putInt("hue_min_bri_pct", minBriPercent)
            .putInt("hue_sat_pct", saturationPercent)
            .putInt("hue_speed_preset", speedPreset)
            .putBoolean("hue_custom_zones", customZonesEnabled)
            .putString("zone_left", leftLights.joinToString(","))
            .putString("zone_center", centerLights.joinToString(","))
            .putString("zone_right", rightLights.joinToString(","))
            .apply()
    }

    private fun triggerBridgePairing() {
        setStatusBanner("Connecting to Hue Bridge at $bridgeIp...")
        ioExecutor.execute {
            if (!isBridgeReachable(bridgeIp)) {
                setStatusBanner("Bridge $bridgeIp unreachable, auto-discovering on Wi-Fi...")
                discoverHueBridgeIp()?.let { found ->
                    bridgeIp = found
                    savePrefs()
                }
            }
            if (hueUsername.isEmpty()) {
                val ok = pairWithBridge()
                if (ok) {
                    discoverAndMapLights(forceAutoMap = false)
                    isSyncEnabled = true
                    savePrefs()
                    updatePillUI()
                    setStatusBanner("Paired with $bridgeIp! Discovered ${allLightsMap.size} lights.")
                    mainHandler.post { if (isSettingsOpen) rebuildSettingsRows(keepSelection = true) }
                } else {
                    setStatusBanner("Press the physical Link button on your Hue Bridge ($bridgeIp), then press OK again!")
                }
            } else {
                discoverAndMapLights(forceAutoMap = false)
                updatePillUI()
                setStatusBanner("Refreshed ${allLightsMap.size} lights from Hue Bridge ($bridgeIp).")
                mainHandler.post { if (isSettingsOpen) rebuildSettingsRows(keepSelection = true) }
            }
        }
    }

    private fun isBridgeReachable(ip: String): Boolean {
        for (scheme in listOf("https", "http")) {
            try {
                val req = Request.Builder()
                    .url("$scheme://$ip/api/config")
                    .get()
                    .build()
                val body = hueHttpClient.newCall(req).execute().use { it.body?.string() ?: "" }
                if (body.contains("bridgeid", ignoreCase = true) || body.contains("modelid", ignoreCase = true)) {
                    activeScheme = scheme
                    return true
                }
            } catch (_: Exception) {}
        }
        return false
    }

    private fun discoverHueBridgeIp(): String? {
        // 1. Official Hue Cloud Discovery endpoint
        try {
            val req = Request.Builder()
                .url("https://discovery.meethue.com")
                .get()
                .build()
            val resp = hueHttpClient.newCall(req).execute().use { it.body?.string() ?: "" }
            val arr = JSONArray(resp)
            for (i in 0 until arr.length()) {
                val ip = arr.optJSONObject(i)?.optString("internalipaddress", "")?.trim() ?: ""
                if (ip.isNotEmpty() && isBridgeReachable(ip)) {
                    return ip
                }
            }
        } catch (_: Exception) {}

        // 2. Fast parallel local /24 subnet scan fallback
        try {
            val prefix = getLocalSubnetPrefix() ?: return null
            val pool = Executors.newFixedThreadPool(24)
            try {
                val tasks = (1..254).map { host ->
                    Callable<String?> {
                        val candidate = "$prefix.$host"
                        if (isBridgeReachable(candidate)) candidate else null
                    }
                }
                val futures = pool.invokeAll(tasks, 2500, TimeUnit.MILLISECONDS)
                for (f in futures) {
                    if (!f.isCancelled) {
                        val res = runCatching { f.get() }.getOrNull()
                        if (res != null) return res
                    }
                }
            } finally {
                pool.shutdownNow()
            }
        } catch (_: Exception) {}
        return null
    }

    private fun getLocalSubnetPrefix(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        val parts = ip.split(".")
                        if (parts.size == 4) {
                            return "${parts[0]}.${parts[1]}.${parts[2]}"
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun previewCurrentBrightnessOnLights() {
        if (hueUsername.isEmpty() || !isSyncEnabled) return
        val previewBri = ((maxBriPercent / 100.0) * 254.0).roundToInt().coerceIn(1, 254)
        val targets = (leftLights + centerLights + rightLights).distinct()
        ioExecutor.execute {
            for (id in targets) {
                val body = """{"on":true,"bri":$previewBri,"transitiontime":2}"""
                val req = Request.Builder()
                    .url("$activeScheme://$bridgeIp/api/$hueUsername/lights/$id/state")
                    .put(body.toRequestBody(jsonMediaType))
                    .build()
                try {
                    hueHttpClient.newCall(req).execute().use { _ -> }
                } catch (_: Exception) {}
            }
        }
    }

    private fun turnOffSyncedLights() {
        if (hueUsername.isEmpty()) return
        val targets = (leftLights + centerLights + rightLights).distinct()
        ioExecutor.execute {
            for (id in targets) {
                val body = """{"on":false,"transitiontime":4}"""
                val req = Request.Builder()
                    .url("$activeScheme://$bridgeIp/api/$hueUsername/lights/$id/state")
                    .put(body.toRequestBody(jsonMediaType))
                    .build()
                try {
                    hueHttpClient.newCall(req).execute().use { _ -> }
                } catch (_: Exception) {}
            }
        }
    }

    private fun flashSingleLightPreview(lightId: String, zone: String) {
        if (hueUsername.isEmpty()) return
        ioExecutor.execute {
            val body = when (zone) {
                "LEFT" -> """{"on":true,"xy":[0.675,0.322],"bri":220,"transitiontime":1}"""
                "CENTER" -> """{"on":true,"xy":[0.215,0.711],"bri":220,"transitiontime":1}"""
                "RIGHT" -> """{"on":true,"xy":[0.167,0.040],"bri":220,"transitiontime":1}"""
                else -> """{"on":false,"transitiontime":2}"""
            }
            val req = Request.Builder()
                .url("$activeScheme://$bridgeIp/api/$hueUsername/lights/$lightId/state")
                .put(body.toRequestBody(jsonMediaType))
                .build()
            try {
                hueHttpClient.newCall(req).execute().use { _ -> }
            } catch (_: Exception) {}
            lastLightStates.remove(lightId)
        }
    }

    private fun testLightZonesPattern() {
        if (hueUsername.isEmpty()) return
        val sendColor = { ids: List<String>, xy: String ->
            for (id in ids) {
                val body = """{"on":true,"xy":$xy,"bri":254,"transitiontime":1}"""
                val req = Request.Builder()
                    .url("$activeScheme://$bridgeIp/api/$hueUsername/lights/$id/state")
                    .put(body.toRequestBody(jsonMediaType))
                    .build()
                try {
                    hueHttpClient.newCall(req).execute().use { _ -> }
                } catch (_: Exception) {}
            }
        }
        sendColor(leftLights, "[0.675,0.322]")   // Red
        sendColor(centerLights, "[0.215,0.711]") // Green
        sendColor(rightLights, "[0.167,0.040]")  // Blue
        lastLightStates.clear()
    }

    private fun showPillTemporarily() {
        mainHandler.post {
            if (isSettingsOpen) return@post
            val p = pillView ?: return@post
            if (activeVideoView == null) {
                p.visibility = View.GONE
                return@post
            }
            p.visibility = View.VISIBLE
            p.alpha = 1f
            mainHandler.removeCallbacks(hidePillRunnable)
            mainHandler.postDelayed(hidePillRunnable, 6000L)
        }
    }

    private fun updatePillBackground(view: TextView, focused: Boolean) {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(50).toFloat()
            if (isSyncEnabled && hueUsername.isNotEmpty()) {
                colors = intArrayOf(
                    Color.parseColor("#7B1FA2"),
                    Color.parseColor("#E91E63"),
                    Color.parseColor("#FF9800")
                )
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            } else {
                setColor(Color.parseColor("#CC1E1E1E"))
            }
            setStroke(
                if (focused) dp(3) else dp(1),
                if (focused) Color.WHITE else Color.parseColor("#66FFFFFF")
            )
        }
        view.background = bg
    }

    private fun updatePillUI(customText: String? = null) {
        mainHandler.post {
            val p = pillView ?: return@post
            val count = (leftLights.size + centerLights.size + rightLights.size)
            p.text = customText ?: when {
                hueUsername.isEmpty() -> "Hue: Pair & Settings ⚙"
                isSyncEnabled -> if (count > 0) "Hue Sync: ON ($count) ⚙" else "Hue Sync: ON ⚙"
                else -> "Hue Sync: OFF ⚙"
            }
            updatePillBackground(p, p.hasFocus())
        }
    }

    private fun startSurfaceScanner() {
        if (loopRunning) return
        loopRunning = true

        val tick = object : Runnable {
            override fun run() {
                if (!loopRunning) return
                val act = currentActivity
                if (act != null) {
                    val now = System.currentTimeMillis()
                    val currentView = activeVideoView
                    val isCurrentValid = currentView != null &&
                        currentView.isAttachedToWindow &&
                        currentView.visibility == View.VISIBLE &&
                        currentView.width > 300 &&
                        currentView.height > 200

                    val videoView = if (isCurrentValid && now - lastSurfaceScanTimeMs < SCAN_INTERVAL_MS * 3) {
                        currentView
                    } else if (now - lastSurfaceScanTimeMs >= SCAN_INTERVAL_MS || !isCurrentValid) {
                        lastSurfaceScanTimeMs = now
                        val root = act.window?.decorView
                        val found = findActiveVideoTextureView(root) ?: findActiveVideoSurface(root)
                        if (found != activeVideoView) {
                            activeVideoView = found
                            if (found != null) showPillTemporarily() else pillView?.visibility = View.GONE
                        }
                        found
                    } else {
                        currentView
                    }

                    if (videoView != null && isSyncEnabled && hueUsername.isNotEmpty() && !isSendingFrame.get()) {
                        try {
                            if (videoView is TextureView && videoView.isAvailable) {
                                videoView.getBitmap(sampleBitmap)
                                processFrame(sampleBitmap)
                            } else if (videoView is SurfaceView && videoView.holder.surface.isValid) {
                                PixelCopy.request(
                                    videoView,
                                    sampleBitmap,
                                    { res -> if (res == PixelCopy.SUCCESS) processFrame(sampleBitmap) },
                                    copyHandler
                                )
                            }
                        } catch (_: Exception) {}
                    }
                }
                mainHandler.postDelayed(this, currentSampleIntervalMs())
            }
        }
        mainHandler.post(tick)
    }

    private fun findActiveVideoTextureView(view: View?): TextureView? {
        if (view == null || view.visibility != View.VISIBLE) return null
        if (view is TextureView && view.width > 300 && view.height > 200 && view.isAvailable) {
            return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findActiveVideoTextureView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun findActiveVideoSurface(view: View?): SurfaceView? {
        if (view == null || view.visibility != View.VISIBLE) return null
        if (view is SurfaceView &&
            !view.javaClass.name.contains("Subtitle", ignoreCase = true) &&
            view.width > 300 &&
            view.height > 200 &&
            view.holder.surface.isValid
        ) {
            return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findActiveVideoSurface(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun processFrame(bmp: Bitmap) {
        if (!isSendingFrame.compareAndSet(false, true)) return
        bmp.getPixels(pixelBuffer, 0, SAMPLE_W, 0, 0, SAMPLE_W, SAMPLE_H)

        var peakFrameChannel = 0
        for (p in pixelBuffer) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val m = max(r, max(g, b))
            if (m > peakFrameChannel) peakFrameChannel = m
        }
        if (peakFrameChannel < 3) {
            isSendingFrame.set(false)
            return
        }

        val left = avgRegion(pixelBuffer, 0, 5)
        val center = avgRegion(pixelBuffer, 5, 11)
        val right = avgRegion(pixelBuffer, 11, 16)

        ioExecutor.execute {
            try {
                leftLights.forEach { sendHueState(it, left) }
                centerLights.forEach { sendHueState(it, center) }
                rightLights.forEach { sendHueState(it, right) }
            } finally {
                isSendingFrame.set(false)
            }
        }
    }

    private fun avgRegion(pixels: IntArray, x0: Int, x1: Int): IntArray {
        var weightedR = 0.0
        var weightedG = 0.0
        var weightedB = 0.0
        var totalWeight = 0.0
        var rawR = 0
        var rawG = 0
        var rawB = 0
        var count = 0
        var maxRegionPeak = 0

        for (y in 1 until (SAMPLE_H - 1)) {
            val rowOffset = y * SAMPLE_W
            for (x in x0 until x1) {
                val p = pixels[rowOffset + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))
                if (maxC > maxRegionPeak) maxRegionPeak = maxC
                rawR += r; rawG += g; rawB += b; count++

                if (maxC >= 12) {
                    val sat = if (maxC > 0) (maxC - minC).toDouble() / maxC.toDouble() else 0.0
                    val luma = maxC.toDouble() / 255.0
                    val weight = 0.5 + sat * 2.5 + luma * 1.5
                    weightedR += r * weight
                    weightedG += g * weight
                    weightedB += b * weight
                    totalWeight += weight
                }
            }
        }

        return if (totalWeight > 0.0) {
            intArrayOf(
                (weightedR / totalWeight).roundToInt().coerceIn(0, 255),
                (weightedG / totalWeight).roundToInt().coerceIn(0, 255),
                (weightedB / totalWeight).roundToInt().coerceIn(0, 255),
                maxRegionPeak
            )
        } else if (count > 0) {
            intArrayOf(rawR / count, rawG / count, rawB / count, maxRegionPeak)
        } else {
            intArrayOf(0, 0, 0, 0)
        }
    }

    private fun rgbToXYBri(rIn: Int, gIn: Int, bIn: Int, peakIn: Int): Triple<Float, Float, Int> {
        val satBoost = (saturationPercent / 100.0f).coerceIn(1.0f, 2.5f)
        val hsv = FloatArray(3)
        Color.RGBToHSV(rIn, gIn, bIn, hsv)
        hsv[1] = (hsv[1] * satBoost).coerceAtMost(1.0f)
        val boostedColor = Color.HSVToColor(hsv)
        val r = Color.red(boostedColor)
        val g = Color.green(boostedColor)
        val b = Color.blue(boostedColor)

        fun gamma(v: Int): Double {
            val n = v / 255.0
            return if (n > 0.04045) ((n + 0.055) / 1.055).pow(2.4) else n / 12.92
        }
        val R = gamma(r); val G = gamma(g); val B = gamma(b)
        val X = R * 0.664511 + G * 0.154324 + B * 0.162028
        val Y = R * 0.283881 + G * 0.668433 + B * 0.047685
        val Z = R * 0.000088 + G * 0.072310 + B * 0.986039
        val sum = X + Y + Z
        val x = if (sum <= 0.0001) 0.3127f else (X / sum).toFloat().coerceIn(0.0f, 1.0f)
        val y = if (sum <= 0.0001) 0.3290f else (Y / sum).toFloat().coerceIn(0.0f, 1.0f)

        val minBri = if (minBriPercent <= 0) 1 else ((minBriPercent / 100.0) * 254.0).roundToInt().coerceIn(1, 250)
        val maxBri = ((maxBriPercent / 100.0) * 254.0).roundToInt().coerceIn(minBri, 254)

        val avgPeak = max(rIn, max(gIn, bIn))
        val effectiveLevel = (avgPeak * 0.65 + peakIn * 0.35) / 255.0
        val perceptual = sqrt(effectiveLevel.coerceIn(0.0, 1.0))
        val bri = (minBri + perceptual * (maxBri - minBri)).roundToInt().coerceIn(minBri, maxBri)
        return Triple(x, y, bri)
    }

    private fun sendHueState(lightId: String, rgbaPeak: IntArray) {
        val (x, y, bri) = rgbToXYBri(rgbaPeak[0], rgbaPeak[1], rgbaPeak[2], rgbaPeak[3])
        val now = System.currentTimeMillis()
        val prev = lastLightStates[lightId]
        if (prev != null &&
            now - prev.timeMs < 3000L &&
            abs(prev.x - x) < 0.015f &&
            abs(prev.y - y) < 0.015f &&
            abs(prev.bri - bri) < 6
        ) {
            return
        }
        lastLightStates[lightId] = LastLightState(x, y, bri, now)

        val body = String.format(
            Locale.US,
            """{"on":true,"xy":[%.4f,%.4f],"bri":%d,"transitiontime":%d}""",
            x,
            y,
            bri,
            currentTransitionTime()
        )
        val req = Request.Builder()
            .url("$activeScheme://$bridgeIp/api/$hueUsername/lights/$lightId/state")
            .put(body.toRequestBody(jsonMediaType))
            .build()
        try {
            hueHttpClient.newCall(req).execute().use { _ -> }
        } catch (_: Exception) {}
    }

    private fun pairWithBridge(): Boolean {
        val payload = """{"devicetype":"nuvio_tv#google_tv_stick"}"""
        for (scheme in listOf("https", "http")) {
            try {
                val req = Request.Builder()
                    .url("$scheme://$bridgeIp/api")
                    .post(payload.toRequestBody(jsonMediaType))
                    .build()
                val resp = hueHttpClient.newCall(req).execute().use { it.body?.string() ?: "" }
                val arr = JSONArray(resp)
                if (arr.length() > 0 && arr.getJSONObject(0).has("success")) {
                    val user = arr.getJSONObject(0).getJSONObject("success").getString("username")
                    hueUsername = user
                    activeScheme = scheme
                    appContext.getSharedPreferences("nuvio_hue_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("hue_username", user)
                        .putString("hue_bridge_ip", bridgeIp)
                        .apply()
                    return true
                }
            } catch (_: Exception) {}
        }
        return false
    }

    private fun discoverAndMapLights(forceAutoMap: Boolean) {
        for (scheme in listOf("https", "http")) {
            try {
                val req = Request.Builder()
                    .url("$scheme://$bridgeIp/api/$hueUsername/lights")
                    .get()
                    .build()
                val resp = hueHttpClient.newCall(req).execute().use { it.body?.string() ?: "" }
                val json = JSONObject(resp)
                val keys = json.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: 999 }
                if (keys.isEmpty()) return
                activeScheme = scheme

                synchronized(allLightsMap) {
                    allLightsMap.clear()
                    for (id in keys) {
                        val rawName = json.optJSONObject(id)?.optString("name", "Light $id") ?: "Light $id"
                        allLightsMap[id] = rawName
                    }
                }

                val hasSavedZones = leftLights.isNotEmpty() || centerLights.isNotEmpty() || rightLights.isNotEmpty()
                if (customZonesEnabled && hasSavedZones && !forceAutoMap) {
                    updatePillUI()
                    return
                }

                val lList = mutableListOf<String>()
                val rList = mutableListOf<String>()
                val cList = mutableListOf<String>()

                for (id in keys) {
                    val name = json.optJSONObject(id)?.optString("name", "")?.lowercase() ?: ""
                    when {
                        name.contains("left") -> lList.add(id)
                        name.contains("right") -> rList.add(id)
                        name.contains("center") || name.contains("hexa") || name.contains("tv") ||
                            name.contains("strip") || name.contains("lamp") || name.contains("play") ||
                            name.contains("bar") || name.contains("sync") || name.contains("living") -> cList.add(id)
                    }
                }

                val matched = (lList + cList + rList).distinct()
                val pool = (if (matched.isNotEmpty()) matched else keys).take(6)

                if (lList.isEmpty() && rList.isEmpty()) {
                    lList.clear(); cList.clear(); rList.clear()
                    when (pool.size) {
                        1 -> cList.add(pool[0])
                        2 -> {
                            lList.add(pool[0])
                            rList.add(pool[1])
                        }
                        else -> {
                            pool.forEachIndexed { idx, id ->
                                when (idx % 3) {
                                    0 -> lList.add(id)
                                    1 -> cList.add(id)
                                    else -> rList.add(id)
                                }
                            }
                        }
                    }
                } else {
                    val used = mutableSetOf<String>()
                    val cleanL = lList.filter { used.add(it) }
                    val cleanR = rList.filter { used.add(it) }
                    val cleanC = cList.filter { used.add(it) }
                    lList.clear(); lList.addAll(cleanL)
                    rList.clear(); rList.addAll(cleanR)
                    cList.clear(); cList.addAll(cleanC)
                }

                leftLights = lList.toList()
                rightLights = rList.toList()
                centerLights = cList.toList()

                savePrefs()
                updatePillUI()
                return
            } catch (_: Exception) {}
        }
    }

    private fun loadSavedZones() {
        val prefs = appContext.getSharedPreferences("nuvio_hue_prefs", Context.MODE_PRIVATE)
        val used = mutableSetOf<String>()
        leftLights = prefs.getString("zone_left", "")?.split(",")?.filter { it.isNotBlank() && used.add(it) } ?: emptyList()
        rightLights = prefs.getString("zone_right", "")?.split(",")?.filter { it.isNotBlank() && used.add(it) } ?: emptyList()
        centerLights = prefs.getString("zone_center", "")?.split(",")?.filter { it.isNotBlank() && used.add(it) } ?: emptyList()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), appContext.resources.displayMetrics).toInt()

    override fun onActivityCreated(a: Activity, b: Bundle?) {}
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}
}

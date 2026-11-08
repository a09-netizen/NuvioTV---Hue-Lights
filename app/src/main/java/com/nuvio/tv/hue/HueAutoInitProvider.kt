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
import android.net.wifi.WifiManager
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
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
    private const val GOVEE_MULTICAST_IP = "239.255.255.250"
    private const val GOVEE_SCAN_PORT = 4001
    private const val GOVEE_LISTEN_PORT = 4002
    private const val GOVEE_CONTROL_PORT = 4003
    private const val NANOLEAF_PORT = 16021

    private const val SCAN_INTERVAL_MS = 1000L
    private const val SAMPLE_W = 16
    private const val SAMPLE_H = 9

    private lateinit var appContext: Context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val discoveryExecutor = Executors.newSingleThreadExecutor()
    private val copyThread = HandlerThread("NuvioHuePixelCopy").apply { start() }
    private val copyHandler = Handler(copyThread.looper)
    private val sampleBitmap = Bitmap.createBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.ARGB_8888)
    private val pixelBuffer = IntArray(SAMPLE_W * SAMPLE_H)
    private val isSendingFrame = AtomicBoolean(false)
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val udpSendSocket: DatagramSocket by lazy {
        DatagramSocket().apply { broadcast = true }
    }

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

    private data class LastRgbState(
        val r: Int,
        val g: Int,
        val b: Int,
        val briPct: Int,
        val timeMs: Long
    )

    private val lastLightStates = ConcurrentHashMap<String, LastLightState>()
    private val lastGoveeStates = ConcurrentHashMap<String, LastRgbState>()
    private val lastNanoleafStates = ConcurrentHashMap<String, LastRgbState>()

    // Philips Hue state
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

    // Govee LAN state (keyed by device IP)
    @Volatile
    private var goveeEnabled: Boolean = true
    private val goveeLightsMap = LinkedHashMap<String, String>() // ip -> display name (e.g. "H6056")
    private var goveeLeftIps = listOf<String>()
    private var goveeCenterIps = listOf<String>()
    private var goveeRightIps = listOf<String>()

    // Nanoleaf OpenAPI state (keyed by device IP -> auth token)
    @Volatile
    private var nanoleafEnabled: Boolean = true
    private val nanoleafTokensMap = LinkedHashMap<String, String>() // ip -> authToken
    private var nanoleafLeftIps = listOf<String>()
    private var nanoleafCenterIps = listOf<String>()
    private var nanoleafRightIps = listOf<String>()

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
        goveeEnabled = prefs.getBoolean("govee_enabled", true)
        nanoleafEnabled = prefs.getBoolean("nanoleaf_enabled", true)
        maxBriPercent = prefs.getInt("hue_max_bri_pct", 100).coerceIn(20, 100)
        minBriPercent = prefs.getInt("hue_min_bri_pct", 30).coerceIn(0, 60)
        saturationPercent = prefs.getInt("hue_sat_pct", 140).coerceIn(100, 200)
        speedPreset = prefs.getInt("hue_speed_preset", 1).coerceIn(0, 3)
        customZonesEnabled = prefs.getBoolean("hue_custom_zones", false)
        loadSavedZones()

        discoveryExecutor.execute {
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
            if (goveeEnabled) {
                scanGoveeLanLights(silent = true)
            }
        }
        app.registerActivityLifecycleCallbacks(this)
    }

    private fun hasAnyConfiguredLights(): Boolean {
        val hueReady = hueUsername.isNotEmpty() && (leftLights.size + centerLights.size + rightLights.size) > 0
        val goveeReady = goveeEnabled && (goveeLeftIps.size + goveeCenterIps.size + goveeRightIps.size) > 0
        val nanoReady = nanoleafEnabled && (nanoleafLeftIps.size + nanoleafCenterIps.size + nanoleafRightIps.size) > 0
        return hueReady || goveeReady || nanoReady || hueUsername.isNotEmpty() || goveeLightsMap.isNotEmpty() || nanoleafTokensMap.isNotEmpty()
    }

    private fun totalActiveLightCount(): Int {
        val hueCount = if (hueUsername.isNotEmpty()) (leftLights.size + centerLights.size + rightLights.size) else 0
        val goveeCount = if (goveeEnabled) (goveeLeftIps.size + goveeCenterIps.size + goveeRightIps.size) else 0
        val nanoCount = if (nanoleafEnabled) (nanoleafLeftIps.size + nanoleafCenterIps.size + nanoleafRightIps.size) else 0
        return hueCount + goveeCount + nanoCount
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
                discoveryExecutor.execute {
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
            dp(450),
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.END or Gravity.CENTER_VERTICAL
        ).apply {
            topMargin = dp(20)
            bottomMargin = dp(20)
            marginEnd = dp(24)
        }

        val headerTitle = TextView(activity).apply {
            text = "Ambient Light Sync (Hue • Govee • Nanoleaf)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17.5f)
            typeface = Typeface.DEFAULT_BOLD
        }

        val headerSub = TextView(activity).apply {
            text = "UP/DOWN: Select • LEFT/RIGHT: Adjust • OK: Action • BACK: Close"
            setTextColor(Color.parseColor("#B0B0C0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
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

        // 1. Master Ambient Screen Sync ON / OFF
        val toggleSync = {
            if (!hasAnyConfiguredLights()) {
                setStatusBanner("Scanning for Govee LAN lights & pairing Hue Bridge ($bridgeIp)...")
                triggerBridgePairing()
                discoveryExecutor.execute { scanGoveeLanLights(silent = false) }
            } else {
                isSyncEnabled = !isSyncEnabled
                savePrefs()
                if (isSyncEnabled) {
                    lastLightStates.clear()
                    lastGoveeStates.clear()
                    lastNanoleafStates.clear()
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
                title = "Ambient Screen Sync",
                subtitle = if (!hasAnyConfiguredLights()) "Pair Hue Bridge or Scan Govee LAN lights below" else "Sync Hue, Govee & Nanoleaf lights (${totalActiveLightCount()} active)",
                valueProvider = {
                    when {
                        !hasAnyConfiguredLights() -> "SETUP"
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
                    clearStateCaches()
                    previewCurrentBrightnessOnLights()
                    refreshAllRowValues()
                },
                onRight = {
                    maxBriPercent = (maxBriPercent + 5).coerceAtMost(100)
                    savePrefs()
                    clearStateCaches()
                    previewCurrentBrightnessOnLights()
                    refreshAllRowValues()
                },
                onCenter = {
                    maxBriPercent = if (maxBriPercent >= 100) 50 else (maxBriPercent + 10).coerceAtMost(100)
                    savePrefs()
                    clearStateCaches()
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
                    clearStateCaches()
                    previewCurrentBrightnessOnLights()
                    refreshAllRowValues()
                },
                onRight = {
                    minBriPercent = (minBriPercent + 5).coerceAtMost(min(60, maxBriPercent - 5))
                    savePrefs()
                    clearStateCaches()
                    previewCurrentBrightnessOnLights()
                    refreshAllRowValues()
                },
                onCenter = {
                    minBriPercent = if (minBriPercent >= 50) 0 else (minBriPercent + 10).coerceAtMost(min(60, maxBriPercent - 5))
                    savePrefs()
                    clearStateCaches()
                    previewCurrentBrightnessOnLights()
                    refreshAllRowValues()
                }
            )
        )

        // 4. Color Saturation Boost
        settingsRowActions.add(
            SettingsRowItem(
                title = "Color Saturation",
                subtitle = "Boost color vibrancy on Hue, Govee & Nanoleaf lights",
                valueProvider = { "◀  $saturationPercent%  ▶" },
                onLeft = {
                    saturationPercent = (saturationPercent - 10).coerceAtLeast(100)
                    savePrefs()
                    clearStateCaches()
                    refreshAllRowValues()
                },
                onRight = {
                    saturationPercent = (saturationPercent + 10).coerceAtMost(200)
                    savePrefs()
                    clearStateCaches()
                    refreshAllRowValues()
                },
                onCenter = {
                    saturationPercent = if (saturationPercent >= 200) 100 else (saturationPercent + 20).coerceAtMost(200)
                    savePrefs()
                    clearStateCaches()
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

        // 6A. Per-Light Zone Assignment Rows (Philips Hue)
        synchronized(allLightsMap) {
            if (allLightsMap.isNotEmpty()) {
                for ((lightId, lightName) in allLightsMap) {
                    val cycleZone = { delta: Int ->
                        val zones = listOf("LEFT", "CENTER", "RIGHT", "OFF")
                        val curr = getHueLightZone(lightId)
                        val idx = zones.indexOf(curr).let { if (it < 0) 3 else it }
                        val nextZone = zones[(idx + delta + zones.size) % zones.size]
                        setHueLightZone(lightId, nextZone)
                        flashSingleHueLightPreview(lightId, nextZone)
                        updatePillUI()
                        refreshAllRowValues()
                    }
                    settingsRowActions.add(
                        SettingsRowItem(
                            title = "💡 Hue: $lightName (ID $lightId)",
                            subtitle = "Philips Hue screen zone assignment",
                            valueProvider = { "◀  ${getHueLightZone(lightId)}  ▶" },
                            onLeft = { cycleZone(-1) },
                            onRight = { cycleZone(1) },
                            onCenter = { cycleZone(1) }
                        )
                    )
                }
            }
        }

        // 6B. Per-Light Zone Assignment Rows (Govee LAN)
        synchronized(goveeLightsMap) {
            if (goveeLightsMap.isNotEmpty()) {
                for ((ip, label) in goveeLightsMap) {
                    val cycleGoveeZone = { delta: Int ->
                        val zones = listOf("LEFT", "CENTER", "RIGHT", "OFF")
                        val curr = getGoveeLightZone(ip)
                        val idx = zones.indexOf(curr).let { if (it < 0) 3 else it }
                        val nextZone = zones[(idx + delta + zones.size) % zones.size]
                        setGoveeLightZone(ip, nextZone)
                        flashSingleGoveeLightPreview(ip, nextZone)
                        updatePillUI()
                        refreshAllRowValues()
                    }
                    settingsRowActions.add(
                        SettingsRowItem(
                            title = "🟢 Govee: $label",
                            subtitle = "Govee LAN UDP ($ip:4003) screen zone",
                            valueProvider = { "◀  ${getGoveeLightZone(ip)}  ▶" },
                            onLeft = { cycleGoveeZone(-1) },
                            onRight = { cycleGoveeZone(1) },
                            onCenter = { cycleGoveeZone(1) }
                        )
                    )
                }
            }
        }

        // 6C. Per-Light Zone Assignment Rows (Nanoleaf OpenAPI)
        synchronized(nanoleafTokensMap) {
            if (nanoleafTokensMap.isNotEmpty()) {
                for ((ip, _) in nanoleafTokensMap) {
                    val cycleNanoZone = { delta: Int ->
                        val zones = listOf("LEFT", "CENTER", "RIGHT", "OFF")
                        val curr = getNanoleafZone(ip)
                        val idx = zones.indexOf(curr).let { if (it < 0) 3 else it }
                        val nextZone = zones[(idx + delta + zones.size) % zones.size]
                        setNanoleafZone(ip, nextZone)
                        updatePillUI()
                        refreshAllRowValues()
                    }
                    settingsRowActions.add(
                        SettingsRowItem(
                            title = "🔺 Nanoleaf ($ip)",
                            subtitle = "Nanoleaf Local OpenAPI (:16021) screen zone",
                            valueProvider = { "◀  ${getNanoleafZone(ip)}  ▶" },
                            onLeft = { cycleNanoZone(-1) },
                            onRight = { cycleNanoZone(1) },
                            onCenter = { cycleNanoZone(1) }
                        )
                    )
                }
            }
        }

        // 7. Govee LAN Scan / Toggle
        val scanGoveeAction = {
            setStatusBanner("Scanning Wi-Fi for Govee LAN lights (Port 4001/4002)...")
            discoveryExecutor.execute {
                scanGoveeLanLights(silent = false)
            }
        }
        val toggleGoveeEnabled = {
            goveeEnabled = !goveeEnabled
            savePrefs()
            updatePillUI()
            refreshAllRowValues()
        }
        settingsRowActions.add(
            SettingsRowItem(
                title = "Govee LAN Lights (${goveeLightsMap.size} found)",
                subtitle = "Enable 'LAN Control' in Govee Home app • OK to scan • LEFT/RIGHT to toggle",
                valueProvider = { if (!goveeEnabled) "DISABLED" else "SCAN LAN" },
                onLeft = toggleGoveeEnabled,
                onRight = toggleGoveeEnabled,
                onCenter = scanGoveeAction
            )
        )

        // 8. Philips Hue Bridge Pair / Rescan
        val pairOrRescan = {
            triggerBridgePairing()
        }
        val autoDiscoverBridge = {
            setStatusBanner("Scanning Wi-Fi network for Philips Hue Bridge...")
            discoveryExecutor.execute {
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
                title = if (hueUsername.isEmpty()) "Pair Hue Bridge ($bridgeIp)" else "Philips Hue Bridge ($bridgeIp)",
                subtitle = if (hueUsername.isEmpty()) "Press Bridge link button then OK • LEFT/RIGHT to auto-discover IP" else "Press OK to rescan Hue bulbs • LEFT/RIGHT to auto-discover IP",
                valueProvider = { if (hueUsername.isEmpty()) "PAIR" else "RESCAN" },
                onLeft = autoDiscoverBridge,
                onRight = autoDiscoverBridge,
                onCenter = pairOrRescan
            )
        )

        // 9. Nanoleaf Experimental Pairing (Port 16021)
        val pairNanoleafAction = {
            setStatusBanner("Scanning Wi-Fi for Nanoleaf (Hold Nanoleaf power button 5-7s first)...")
            discoveryExecutor.execute {
                scanAndPairNanoleaf()
            }
        }
        settingsRowActions.add(
            SettingsRowItem(
                title = "Nanoleaf Panels (Experimental - ${nanoleafTokensMap.size} paired)",
                subtitle = "Hold Nanoleaf controller power button 5-7s until LEDs flash, then press OK",
                valueProvider = { "PAIR" },
                onLeft = pairNanoleafAction,
                onRight = pairNanoleafAction,
                onCenter = pairNanoleafAction
            )
        )

        // 10. Test Light Zones (Hue + Govee + Nanoleaf)
        val runZoneTest = {
            setStatusBanner("Flashing zones: Left = Red, Center = Green, Right = Blue...")
            ioExecutor.execute {
                testLightZonesPattern()
                mainHandler.postDelayed({ setStatusBanner(null) }, 2200L)
            }
        }
        settingsRowActions.add(
            SettingsRowItem(
                title = "Test All Light Zones",
                subtitle = "Flash Red (Left), Green (Center), Blue (Right) on Hue & Govee lights",
                valueProvider = { "TEST" },
                onLeft = runZoneTest,
                onRight = runZoneTest,
                onCenter = runZoneTest
            )
        )

        // 11. Reset to Defaults
        val resetDefaults = {
            maxBriPercent = 100
            minBriPercent = 30
            saturationPercent = 140
            speedPreset = 1
            customZonesEnabled = false
            savePrefs()
            clearStateCaches()
            discoveryExecutor.execute {
                if (hueUsername.isNotEmpty()) {
                    discoverAndMapLights(forceAutoMap = true)
                }
                if (goveeEnabled && goveeLightsMap.isNotEmpty()) {
                    autoMapGoveeZones()
                }
                mainHandler.post {
                    setStatusBanner("Reset to defaults & auto-mapped light zones")
                    if (isSettingsOpen) rebuildSettingsRows(keepSelection = true)
                }
            }
        }
        settingsRowActions.add(
            SettingsRowItem(
                title = "Reset Sync Defaults",
                subtitle = "Restore default brightness, saturation, speed & auto-zones",
                valueProvider = { "RESET" },
                onLeft = resetDefaults,
                onRight = resetDefaults,
                onCenter = resetDefaults
            )
        )

        // 12. Close Menu
        settingsRowActions.add(
            SettingsRowItem(
                title = "Close Settings",
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

    private fun clearStateCaches() {
        lastLightStates.clear()
        lastGoveeStates.clear()
        lastNanoleafStates.clear()
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

    private fun getHueLightZone(lightId: String): String = when {
        leftLights.contains(lightId) -> "LEFT"
        centerLights.contains(lightId) -> "CENTER"
        rightLights.contains(lightId) -> "RIGHT"
        else -> "OFF"
    }

    private fun setHueLightZone(lightId: String, zone: String) {
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

    private fun getGoveeLightZone(ip: String): String = when {
        goveeLeftIps.contains(ip) -> "LEFT"
        goveeCenterIps.contains(ip) -> "CENTER"
        goveeRightIps.contains(ip) -> "RIGHT"
        else -> "OFF"
    }

    private fun setGoveeLightZone(ip: String, zone: String) {
        val l = goveeLeftIps.toMutableList().apply { remove(ip) }
        val c = goveeCenterIps.toMutableList().apply { remove(ip) }
        val r = goveeRightIps.toMutableList().apply { remove(ip) }
        when (zone) {
            "LEFT" -> l.add(ip)
            "CENTER" -> c.add(ip)
            "RIGHT" -> r.add(ip)
        }
        goveeLeftIps = l
        goveeCenterIps = c
        goveeRightIps = r
        customZonesEnabled = true
        savePrefs()
    }

    private fun getNanoleafZone(ip: String): String = when {
        nanoleafLeftIps.contains(ip) -> "LEFT"
        nanoleafCenterIps.contains(ip) -> "CENTER"
        nanoleafRightIps.contains(ip) -> "RIGHT"
        else -> "OFF"
    }

    private fun setNanoleafZone(ip: String, zone: String) {
        val l = nanoleafLeftIps.toMutableList().apply { remove(ip) }
        val c = nanoleafCenterIps.toMutableList().apply { remove(ip) }
        val r = nanoleafRightIps.toMutableList().apply { remove(ip) }
        when (zone) {
            "LEFT" -> l.add(ip)
            "CENTER" -> c.add(ip)
            "RIGHT" -> r.add(ip)
        }
        nanoleafLeftIps = l
        nanoleafCenterIps = c
        nanoleafRightIps = r
        customZonesEnabled = true
        savePrefs()
    }

    private fun savePrefs() {
        val goveeDevicesStr = synchronized(goveeLightsMap) {
            goveeLightsMap.entries.joinToString(";") { "${it.key}|${it.value}" }
        }
        val nanoDevicesStr = synchronized(nanoleafTokensMap) {
            nanoleafTokensMap.entries.joinToString(";") { "${it.key}|${it.value}" }
        }
        appContext.getSharedPreferences("nuvio_hue_prefs", Context.MODE_PRIVATE).edit()
            .putString("hue_bridge_ip", bridgeIp)
            .putBoolean("hue_sync_enabled", isSyncEnabled)
            .putBoolean("govee_enabled", goveeEnabled)
            .putBoolean("nanoleaf_enabled", nanoleafEnabled)
            .putInt("hue_max_bri_pct", maxBriPercent)
            .putInt("hue_min_bri_pct", minBriPercent)
            .putInt("hue_sat_pct", saturationPercent)
            .putInt("hue_speed_preset", speedPreset)
            .putBoolean("hue_custom_zones", customZonesEnabled)
            .putString("zone_left", leftLights.joinToString(","))
            .putString("zone_center", centerLights.joinToString(","))
            .putString("zone_right", rightLights.joinToString(","))
            .putString("govee_known_devices", goveeDevicesStr)
            .putString("govee_left", goveeLeftIps.joinToString(","))
            .putString("govee_center", goveeCenterIps.joinToString(","))
            .putString("govee_right", goveeRightIps.joinToString(","))
            .putString("nano_known_devices", nanoDevicesStr)
            .putString("nano_left", nanoleafLeftIps.joinToString(","))
            .putString("nano_center", nanoleafCenterIps.joinToString(","))
            .putString("nano_right", nanoleafRightIps.joinToString(","))
            .apply()
    }

    private fun loadSavedZones() {
        val prefs = appContext.getSharedPreferences("nuvio_hue_prefs", Context.MODE_PRIVATE)
        val usedHue = mutableSetOf<String>()
        leftLights = prefs.getString("zone_left", "")?.split(",")?.filter { it.isNotBlank() && usedHue.add(it) } ?: emptyList()
        rightLights = prefs.getString("zone_right", "")?.split(",")?.filter { it.isNotBlank() && usedHue.add(it) } ?: emptyList()
        centerLights = prefs.getString("zone_center", "")?.split(",")?.filter { it.isNotBlank() && usedHue.add(it) } ?: emptyList()

        synchronized(goveeLightsMap) {
            goveeLightsMap.clear()
            prefs.getString("govee_known_devices", "")?.split(";")?.forEach { entry ->
                val parts = entry.split("|")
                if (parts.size == 2 && parts[0].isNotBlank()) {
                    goveeLightsMap[parts[0]] = parts[1]
                }
            }
        }
        val usedGovee = mutableSetOf<String>()
        goveeLeftIps = prefs.getString("govee_left", "")?.split(",")?.filter { it.isNotBlank() && usedGovee.add(it) } ?: emptyList()
        goveeCenterIps = prefs.getString("govee_center", "")?.split(",")?.filter { it.isNotBlank() && usedGovee.add(it) } ?: emptyList()
        goveeRightIps = prefs.getString("govee_right", "")?.split(",")?.filter { it.isNotBlank() && usedGovee.add(it) } ?: emptyList()

        synchronized(nanoleafTokensMap) {
            nanoleafTokensMap.clear()
            prefs.getString("nano_known_devices", "")?.split(";")?.forEach { entry ->
                val parts = entry.split("|")
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    nanoleafTokensMap[parts[0]] = parts[1]
                }
            }
        }
        val usedNano = mutableSetOf<String>()
        nanoleafLeftIps = prefs.getString("nano_left", "")?.split(",")?.filter { it.isNotBlank() && usedNano.add(it) } ?: emptyList()
        nanoleafCenterIps = prefs.getString("nano_center", "")?.split(",")?.filter { it.isNotBlank() && usedNano.add(it) } ?: emptyList()
        nanoleafRightIps = prefs.getString("nano_right", "")?.split(",")?.filter { it.isNotBlank() && usedNano.add(it) } ?: emptyList()
    }

    // =========================================================================
    // GOVEE LOCAL LAN DISCOVERY & UDP SYNC (Ports 4001 / 4002 / 4003)
    // =========================================================================

    private fun scanGoveeLanLights(silent: Boolean) {
        val wifi = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifi?.createMulticastLock("NuvioGoveeScan")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }

        val discovered = LinkedHashMap<String, String>()
        val scanJson = """{"msg":{"cmd":"scan","data":{"account_topic":"reserve"}}}"""
        val scanBytes = scanJson.toByteArray(Charsets.UTF_8)

        var listenSocket: DatagramSocket? = null
        try {
            listenSocket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 350
                runCatching { bind(InetSocketAddress(GOVEE_LISTEN_PORT)) }.onFailure {
                    bind(InetSocketAddress(0))
                }
            }

            // 1. Send multicast scan to 239.255.255.250:4001
            runCatching {
                val multicastAddr = InetAddress.getByName(GOVEE_MULTICAST_IP)
                listenSocket.send(DatagramPacket(scanBytes, scanBytes.size, multicastAddr, GOVEE_SCAN_PORT))
            }

            // 2. Send subnet broadcast & fast unicast sweep on port 4001 (in case router blocks multicast)
            val prefix = getLocalSubnetPrefix()
            if (prefix != null) {
                runCatching {
                    val bcastAddr = InetAddress.getByName("$prefix.255")
                    listenSocket.send(DatagramPacket(scanBytes, scanBytes.size, bcastAddr, GOVEE_SCAN_PORT))
                }
                for (host in 1..254) {
                    runCatching {
                        val addr = InetAddress.getByName("$prefix.$host")
                        listenSocket.send(DatagramPacket(scanBytes, scanBytes.size, addr, GOVEE_SCAN_PORT))
                    }
                }
            }

            val buf = ByteArray(2048)
            val deadline = System.currentTimeMillis() + 1800L
            while (System.currentTimeMillis() < deadline) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    listenSocket.receive(pkt)
                    val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    val msg = JSONObject(text).optJSONObject("msg") ?: continue
                    if (msg.optString("cmd") == "scan") {
                        val data = msg.optJSONObject("data")
                        val ip = data?.optString("ip", "")?.takeIf { it.isNotBlank() }
                            ?: pkt.address?.hostAddress
                            ?: continue
                        val sku = data?.optString("sku", "Govee")?.takeIf { it.isNotBlank() } ?: "Govee"
                        discovered[ip] = "$sku ($ip)"
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        } finally {
            runCatching { listenSocket?.close() }
            runCatching { if (multicastLock?.isHeld == true) multicastLock.release() }
        }

        if (discovered.isNotEmpty()) {
            synchronized(goveeLightsMap) {
                for ((ip, label) in discovered) {
                    goveeLightsMap[ip] = label
                }
            }
            val hasGoveeZones = goveeLeftIps.isNotEmpty() || goveeCenterIps.isNotEmpty() || goveeRightIps.isNotEmpty()
            if (!customZonesEnabled || !hasGoveeZones) {
                autoMapGoveeZones()
            }
            if (!isSyncEnabled && hueUsername.isEmpty()) {
                isSyncEnabled = true
            }
            savePrefs()
            updatePillUI()
            if (!silent) {
                setStatusBanner("Found ${discovered.size} Govee LAN light(s)! Assigned to screen zones.")
                mainHandler.post { if (isSettingsOpen) rebuildSettingsRows(keepSelection = true) }
            }
        } else if (!silent) {
            setStatusBanner("No Govee LAN lights replied. Ensure 'LAN Control' is enabled in the Govee Home phone app.")
            mainHandler.post { if (isSettingsOpen) rebuildSettingsRows(keepSelection = true) }
        }
    }

    private fun autoMapGoveeZones() {
        val ips = synchronized(goveeLightsMap) { goveeLightsMap.keys.toList() }
        val l = mutableListOf<String>()
        val c = mutableListOf<String>()
        val r = mutableListOf<String>()
        when (ips.size) {
            0 -> {}
            1 -> c.add(ips[0])
            2 -> {
                l.add(ips[0])
                r.add(ips[1])
            }
            else -> {
                ips.forEachIndexed { idx, ip ->
                    when (idx % 3) {
                        0 -> l.add(ip)
                        1 -> c.add(ip)
                        else -> r.add(ip)
                    }
                }
            }
        }
        goveeLeftIps = l
        goveeCenterIps = c
        goveeRightIps = r
        savePrefs()
    }

    private fun sendGoveeState(ip: String, rgbaPeak: IntArray) {
        val (r, g, b, briPct) = rgbToBoostedRgbBriPct(rgbaPeak[0], rgbaPeak[1], rgbaPeak[2], rgbaPeak[3])
        val now = System.currentTimeMillis()
        val prev = lastGoveeStates[ip]
        if (prev != null &&
            now - prev.timeMs < 3000L &&
            abs(prev.r - r) < 6 &&
            abs(prev.g - g) < 6 &&
            abs(prev.b - b) < 6 &&
            abs(prev.briPct - briPct) < 4
        ) {
            return
        }
        lastGoveeStates[ip] = LastRgbState(r, g, b, briPct, now)

        try {
            val addr = InetAddress.getByName(ip)
            val colorCmd = """{"msg":{"cmd":"colorwc","data":{"color":{"r":$r,"g":$g,"b":$b},"colorTemInKelvin":0}}}"""
            val colorBytes = colorCmd.toByteArray(Charsets.UTF_8)
            udpSendSocket.send(DatagramPacket(colorBytes, colorBytes.size, addr, GOVEE_CONTROL_PORT))

            if (prev == null || abs(prev.briPct - briPct) >= 4) {
                val briCmd = """{"msg":{"cmd":"brightness","data":{"value":$briPct}}}"""
                val briBytes = briCmd.toByteArray(Charsets.UTF_8)
                udpSendSocket.send(DatagramPacket(briBytes, briBytes.size, addr, GOVEE_CONTROL_PORT))
            }
        } catch (_: Exception) {}
    }

    private fun sendGoveeRawCommand(ip: String, jsonCmd: String) {
        try {
            val addr = InetAddress.getByName(ip)
            val bytes = jsonCmd.toByteArray(Charsets.UTF_8)
            udpSendSocket.send(DatagramPacket(bytes, bytes.size, addr, GOVEE_CONTROL_PORT))
        } catch (_: Exception) {}
    }

    private fun flashSingleGoveeLightPreview(ip: String, zone: String) {
        ioExecutor.execute {
            when (zone) {
                "LEFT" -> {
                    sendGoveeRawCommand(ip, """{"msg":{"cmd":"turn","data":{"value":1}}}""")
                    sendGoveeRawCommand(ip, """{"msg":{"cmd":"colorwc","data":{"color":{"r":255,"g":0,"b":0},"colorTemInKelvin":0}}}""")
                }
                "CENTER" -> {
                    sendGoveeRawCommand(ip, """{"msg":{"cmd":"turn","data":{"value":1}}}""")
                    sendGoveeRawCommand(ip, """{"msg":{"cmd":"colorwc","data":{"color":{"r":0,"g":255,"b":0},"colorTemInKelvin":0}}}""")
                }
                "RIGHT" -> {
                    sendGoveeRawCommand(ip, """{"msg":{"cmd":"turn","data":{"value":1}}}""")
                    sendGoveeRawCommand(ip, """{"msg":{"cmd":"colorwc","data":{"color":{"r":0,"g":60,"b":255},"colorTemInKelvin":0}}}""")
                }
                else -> {
                    sendGoveeRawCommand(ip, """{"msg":{"cmd":"turn","data":{"value":0}}}""")
                }
            }
            lastGoveeStates.remove(ip)
        }
    }

    // =========================================================================
    // NANOLEAF LOCAL OPENAPI (Experimental - Port 16021)
    // =========================================================================

    private fun scanAndPairNanoleaf() {
        val prefix = getLocalSubnetPrefix()
        if (prefix == null) {
            setStatusBanner("Could not detect Wi-Fi subnet for Nanoleaf scan.")
            return
        }
        val pool = Executors.newFixedThreadPool(24)
        var pairedCount = 0
        try {
            val tasks = (1..254).map { host ->
                Callable<Pair<String, String>?> {
                    val ip = "$prefix.$host"
                    try {
                        val req = Request.Builder()
                            .url("http://$ip:$NANOLEAF_PORT/api/v1/new")
                            .post("".toRequestBody(jsonMediaType))
                            .build()
                        val resp = hueHttpClient.newCall(req).execute().use { it.body?.string() ?: "" }
                        val token = JSONObject(resp).optString("auth_token", "")
                        if (token.isNotBlank()) ip to token else null
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            val futures = pool.invokeAll(tasks, 3000, TimeUnit.MILLISECONDS)
            for (f in futures) {
                if (!f.isCancelled) {
                    val pair = runCatching { f.get() }.getOrNull()
                    if (pair != null) {
                        synchronized(nanoleafTokensMap) {
                            nanoleafTokensMap[pair.first] = pair.second
                        }
                        if (!nanoleafLeftIps.contains(pair.first) &&
                            !nanoleafCenterIps.contains(pair.first) &&
                            !nanoleafRightIps.contains(pair.first)
                        ) {
                            nanoleafCenterIps = nanoleafCenterIps + pair.first
                        }
                        pairedCount++
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            pool.shutdownNow()
        }

        if (pairedCount > 0) {
            savePrefs()
            updatePillUI()
            setStatusBanner("Paired $pairedCount Nanoleaf device(s)!")
            mainHandler.post { if (isSettingsOpen) rebuildSettingsRows(keepSelection = true) }
        } else {
            setStatusBanner("No Nanoleaf in pairing mode found on port 16021 (Hold power button 5-7s first).")
        }
    }

    private fun sendNanoleafState(ip: String, rgbaPeak: IntArray) {
        val token = synchronized(nanoleafTokensMap) { nanoleafTokensMap[ip] } ?: return
        val (r, g, b, briPct) = rgbToBoostedRgbBriPct(rgbaPeak[0], rgbaPeak[1], rgbaPeak[2], rgbaPeak[3])
        val now = System.currentTimeMillis()
        val prev = lastNanoleafStates[ip]
        if (prev != null &&
            now - prev.timeMs < 3000L &&
            abs(prev.r - r) < 8 &&
            abs(prev.g - g) < 8 &&
            abs(prev.b - b) < 8 &&
            abs(prev.briPct - briPct) < 5
        ) {
            return
        }
        lastNanoleafStates[ip] = LastRgbState(r, g, b, briPct, now)

        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val hueInt = hsv[0].roundToInt().coerceIn(0, 360)
        val satInt = (hsv[1] * 100f).roundToInt().coerceIn(0, 100)
        val body = """{"on":{"value":true},"hue":{"value":$hueInt},"sat":{"value":$satInt},"brightness":{"value":$briPct,"duration":1}}"""
        val req = Request.Builder()
            .url("http://$ip:$NANOLEAF_PORT/api/v1/$token/state")
            .put(body.toRequestBody(jsonMediaType))
            .build()
        try {
            hueHttpClient.newCall(req).execute().use { _ -> }
        } catch (_: Exception) {}
    }

    // =========================================================================
    // PHILIPS HUE BRIDGE DISCOVERY & SYNC
    // =========================================================================

    private fun triggerBridgePairing() {
        setStatusBanner("Connecting to Hue Bridge at $bridgeIp...")
        discoveryExecutor.execute {
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
                    setStatusBanner("Paired with Hue Bridge ($bridgeIp)! Discovered ${allLightsMap.size} bulbs.")
                    mainHandler.post { if (isSettingsOpen) rebuildSettingsRows(keepSelection = true) }
                } else {
                    setStatusBanner("Press the physical Link button on your Hue Bridge ($bridgeIp), then press OK again!")
                }
            } else {
                discoverAndMapLights(forceAutoMap = false)
                updatePillUI()
                setStatusBanner("Refreshed ${allLightsMap.size} bulbs from Hue Bridge ($bridgeIp).")
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
        if (!isSyncEnabled) return
        val previewBriHue = ((maxBriPercent / 100.0) * 254.0).roundToInt().coerceIn(1, 254)
        val hueTargets = (leftLights + centerLights + rightLights).distinct()
        val goveeTargets = (goveeLeftIps + goveeCenterIps + goveeRightIps).distinct()
        ioExecutor.execute {
            if (hueUsername.isNotEmpty()) {
                for (id in hueTargets) {
                    val body = """{"on":true,"bri":$previewBriHue,"transitiontime":2}"""
                    val req = Request.Builder()
                        .url("$activeScheme://$bridgeIp/api/$hueUsername/lights/$id/state")
                        .put(body.toRequestBody(jsonMediaType))
                        .build()
                    try {
                        hueHttpClient.newCall(req).execute().use { _ -> }
                    } catch (_: Exception) {}
                }
            }
            if (goveeEnabled) {
                for (ip in goveeTargets) {
                    sendGoveeRawCommand(ip, """{"msg":{"cmd":"turn","data":{"value":1}}}""")
                    sendGoveeRawCommand(ip, """{"msg":{"cmd":"brightness","data":{"value":$maxBriPercent}}}""")
                }
            }
        }
    }

    private fun turnOffSyncedLights() {
        val hueTargets = (leftLights + centerLights + rightLights).distinct()
        val goveeTargets = (goveeLeftIps + goveeCenterIps + goveeRightIps).distinct()
        ioExecutor.execute {
            if (hueUsername.isNotEmpty()) {
                for (id in hueTargets) {
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
            if (goveeEnabled) {
                for (ip in goveeTargets) {
                    sendGoveeRawCommand(ip, """{"msg":{"cmd":"turn","data":{"value":0}}}""")
                }
            }
        }
    }

    private fun flashSingleHueLightPreview(lightId: String, zone: String) {
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
        if (hueUsername.isNotEmpty()) {
            val sendHueColor = { ids: List<String>, xy: String ->
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
            sendHueColor(leftLights, "[0.675,0.322]")   // Red
            sendHueColor(centerLights, "[0.215,0.711]") // Green
            sendHueColor(rightLights, "[0.167,0.040]")  // Blue
        }
        if (goveeEnabled) {
            goveeLeftIps.forEach { flashSingleGoveeLightPreview(it, "LEFT") }
            goveeCenterIps.forEach { flashSingleGoveeLightPreview(it, "CENTER") }
            goveeRightIps.forEach { flashSingleGoveeLightPreview(it, "RIGHT") }
        }
        clearStateCaches()
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
            if (isSyncEnabled && hasAnyConfiguredLights()) {
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
            val count = totalActiveLightCount()
            p.text = customText ?: when {
                !hasAnyConfiguredLights() -> "Light Sync: Setup ⚙"
                isSyncEnabled -> if (count > 0) "Light Sync: ON ($count) ⚙" else "Light Sync: ON ⚙"
                else -> "Light Sync: OFF ⚙"
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

                    if (videoView != null && isSyncEnabled && hasAnyConfiguredLights() && !isSendingFrame.get()) {
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
                // 1. Dispatch Govee LAN UDP packets first (non-blocking sub-millisecond UDP)
                if (goveeEnabled) {
                    goveeLeftIps.forEach { sendGoveeState(it, left) }
                    goveeCenterIps.forEach { sendGoveeState(it, center) }
                    goveeRightIps.forEach { sendGoveeState(it, right) }
                }

                // 2. Dispatch Philips Hue Bridge HTTPS calls
                if (hueUsername.isNotEmpty()) {
                    leftLights.forEach { sendHueState(it, left) }
                    centerLights.forEach { sendHueState(it, center) }
                    rightLights.forEach { sendHueState(it, right) }
                }

                // 3. Dispatch Nanoleaf Local OpenAPI calls
                if (nanoleafEnabled && nanoleafTokensMap.isNotEmpty()) {
                    nanoleafLeftIps.forEach { sendNanoleafState(it, left) }
                    nanoleafCenterIps.forEach { sendNanoleafState(it, center) }
                    nanoleafRightIps.forEach { sendNanoleafState(it, right) }
                }
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

    private fun rgbToBoostedRgbBriPct(rIn: Int, gIn: Int, bIn: Int, peakIn: Int): IntArray {
        val satBoost = (saturationPercent / 100.0f).coerceIn(1.0f, 2.5f)
        val hsv = FloatArray(3)
        Color.RGBToHSV(rIn, gIn, bIn, hsv)
        hsv[1] = (hsv[1] * satBoost).coerceAtMost(1.0f)
        val boostedColor = Color.HSVToColor(hsv)
        val r = Color.red(boostedColor).coerceIn(0, 255)
        val g = Color.green(boostedColor).coerceIn(0, 255)
        val b = Color.blue(boostedColor).coerceIn(0, 255)

        val minPct = if (minBriPercent <= 0) 1 else minBriPercent.coerceIn(1, 95)
        val maxPct = maxBriPercent.coerceIn(minPct, 100)
        val avgPeak = max(rIn, max(gIn, bIn))
        val effectiveLevel = (avgPeak * 0.65 + peakIn * 0.35) / 255.0
        val perceptual = sqrt(effectiveLevel.coerceIn(0.0, 1.0))
        val briPct = (minPct + perceptual * (maxPct - minPct)).roundToInt().coerceIn(minPct, maxPct)
        return intArrayOf(r, g, b, briPct)
    }

    private fun rgbToXYBri(rIn: Int, gIn: Int, bIn: Int, peakIn: Int): Triple<Float, Float, Int> {
        val boosted = rgbToBoostedRgbBriPct(rIn, gIn, bIn, peakIn)
        val r = boosted[0]
        val g = boosted[1]
        val b = boosted[2]

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

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), appContext.resources.displayMetrics).toInt()

    override fun onActivityCreated(a: Activity, b: Bundle?) {}
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}
}

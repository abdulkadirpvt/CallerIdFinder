package com.callerid.finder

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.os.PowerManager
import android.view.*
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URL

class CallerOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val CHANNEL_ID = "caller_id_channel"
        private const val NOTIF_ID = 1
        private const val API_URL = "https://database-sigma-nine.vercel.app/number/%s?api_key=YOUR-PASSWORD"
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "CallerIdFinder:overlay"
        ).also { it.acquire(30_000) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Looking up caller..."))
        val number = intent?.getStringExtra("number") ?: return START_NOT_STICKY
        val normalized = PhoneUtils.normalizeNumber(number) ?: run {
            showOverlay("Invalid number: $number")
            return START_NOT_STICKY
        }
        showOverlay("Searching: $normalized")
        fetchCallerInfo(normalized)
        return START_NOT_STICKY
    }

    private fun fetchCallerInfo(number: String) {
        scope.launch {
            // Try Telegram bot first
            TelegramClient.init(applicationContext)
            var result: String? = null
            if (TelegramClient.isReady()) {
                showOverlay("Searching via Telegram...")
                result = TelegramClient.queryBot(number)
            }

            // Fallback to REST API
            if (result == null) {
                var attempt = 0
                while (attempt < 10) {
                    attempt++
                    showOverlay("Searching ($attempt/10)...")
                    result = withContext(Dispatchers.IO) {
                        runCatching {
                            val response = URL(API_URL.format(number)).readText()
                            if (response.contains("API Error", ignoreCase = true))
                                throw Exception("API Error, retrying...")
                            parseResponse(response)
                        }.getOrElse { null }
                    }
                    if (result != null) break
                    delay(2000)
                }
            }

            showOverlay(result ?: "No info found")
            delay(20000)
            stopSelf()
        }
    }

    private fun parseResponse(raw: String): String {
        val jsonStart = raw.indexOf('{')
        if (jsonStart == -1) return "No info found"
        val json = raw.substring(jsonStart)
        return try {
            val obj = JSONObject(json)
            val r = obj.optJSONArray("results")?.optJSONObject(0) ?: obj
            // Also check top-level obj for address in case it's not in results
            val address = r.optString("address").ifBlank { obj.optString("address") }
            buildString {
                val name = r.optString("name")
                val fname = r.optString("fname")
                val id = r.optString("id")
                val alt = r.optString("alt")
                val mobile = r.optString("mobile")
                if (name.isNotBlank()) append("👤 $name")
                if (fname.isNotBlank()) append(" (S/O $fname)")
                if (alt.isNotBlank()) append("\n📱 $alt")
                if (id.isNotBlank()) append("\n🆔 $id")
                if (address.isNotBlank()) append("\n📍 $address")
                if (isEmpty()) append("No info found")
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun showOverlay(text: String) {
        removeOverlay()
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_layout, null).apply {
            findViewById<TextView>(R.id.tvCallerInfo).text = text
            findViewById<View>(R.id.btnClose).setOnClickListener { stopSelf() }
        }

        val yOffsetPx = (200 * resources.displayMetrics.density).toInt()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            x = 0
            y = yOffsetPx
        }

        windowManager.addView(overlayView, params)

        // Make draggable
        var initialX = 0; var initialY = yOffsetPx
        var touchX = 0f; var touchY = 0f
        overlayView!!.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    touchX = event.rawX; touchY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(overlayView, params)
                }
            }
            false
        }
    }

    private fun removeOverlay() {
        overlayView?.let { windowManager.removeView(it); overlayView = null }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CallerID Finder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Caller ID", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        removeOverlay()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

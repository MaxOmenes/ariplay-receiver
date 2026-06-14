package com.example.airplayreceiverunai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat

class AirPlayService : Service(), AirPlayCallback {

    private val binder = LocalBinder()

    private var nsdService: AirPlayNsdService? = null
    private var audioPlayer: AirPlayAudioPlayer? = null
    private var videoDecoder: AirPlayDecoder? = null

    // Callbacks to MainActivity
    private var videoDimensionListener: ((Int, Int) -> Unit)? = null
    private var isActivityBound = false

    inner class LocalBinder : Binder() {
        fun getService(): AirPlayService = this@AirPlayService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AirPlayService onCreate")
        startForeground(NOTIFICATION_ID, createNotification())

        audioPlayer = AirPlayAudioPlayer()
        val mac = generateMacAddress()

        // Start C++ AirPlay Server
        AirPlayBridge.nativeStart(0, "AirPlay TV", mac, this)

        // Get the assigned port and start mDNS
        val port = AirPlayBridge.nativeGetPort()
        nsdService = AirPlayNsdService(this, "AirPlay TV", mac, port)
        nsdService?.register()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "AirPlayService onDestroy")
        AirPlayBridge.nativeStop()
        nsdService?.unregister()
        audioPlayer?.release()
        videoDecoder?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder {
        isActivityBound = true
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isActivityBound = false
        videoDimensionListener = null
        // We do NOT stop the service here! It keeps running in the background.
        // We only clear the surface because the activity is gone.
        setSurface(null)
        return true
    }

    // ── API for MainActivity ──────────────────────────────────────────────────

    fun setSurface(surface: Surface?) {
        videoDecoder?.release()
        videoDecoder = null

        if (surface != null) {
            videoDecoder = AirPlayDecoder(surface) { w, h ->
                videoDimensionListener?.invoke(w, h)
            }
        }
    }

    fun setVideoDimensionListener(listener: (Int, Int) -> Unit) {
        this.videoDimensionListener = listener
    }

    // ── AirPlayCallback ───────────────────────────────────────────────────────

    override fun onConnected() {
        Log.i(TAG, "Client connected!")
        wakeUpActivity()
    }

    override fun onDisconnected() {
        Log.i(TAG, "Client disconnected!")
        audioPlayer?.release()
        audioPlayer = AirPlayAudioPlayer() // Reset for next connection
    }

    override fun onVideoData(data: ByteArray, isH265: Boolean) {
        if (!isActivityBound) {
            wakeUpActivity()
        }
        videoDecoder?.onVideoData(data, isH265)
    }

    override fun onAudioData(data: ByteArray, ct: Int) {
        audioPlayer?.onAudioData(data, ct)
    }

    private fun wakeUpActivity() {
        if (isActivityBound) return
        Log.i(TAG, "Waking up MainActivity...")
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MainActivity. Missing SYSTEM_ALERT_WINDOW?", e)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun createNotification(): Notification {
        val channelId = "airplay_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "AirPlay Receiver Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("AirPlay Receiver is active")
            .setContentText("Waiting for connections...")
            .setSmallIcon(android.R.drawable.ic_media_play) // Standard icon
            .setOngoing(true)
            .build()
    }

    private fun generateMacAddress(): String {
        val bytes = ByteArray(6)
        java.util.Random().nextBytes(bytes)
        bytes[0] = (bytes[0].toInt() and 0xFE).toByte() // Unicast
        bytes[0] = (bytes[0].toInt() or 0x02).toByte()  // Locally administered
        return bytes.joinToString(":") { String.format("%02X", it) }
    }

    companion object {
        private const val TAG = "AirPlayService"
        private const val NOTIFICATION_ID = 1001
    }
}

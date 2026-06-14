package com.example.airplayreceiverunai

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback, AirPlayCallback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var surfaceContainer: FrameLayout

    // Video dimensions reported by the decoder; 0 = unknown (wait for first frame)
    @Volatile private var videoWidth: Int = 0
    @Volatile private var videoHeight: Int = 0
    private lateinit var overlay: ConstraintLayout
    private lateinit var statusText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var errorCard: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var ring1: View
    private lateinit var ring2: View
    private lateinit var ring3: View

    @Volatile private var decoder: AirPlayDecoder? = null
    private var audioPlayer: AirPlayAudioPlayer? = null
    private var nsdService: AirPlayNsdService? = null
    private var pulseAnimator: AnimatorSet? = null

    private val deviceName = "MiTele"
    private val serverPort = 7000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceView)
        surfaceContainer = findViewById(R.id.surfaceContainer)
        overlay = findViewById(R.id.overlay)
        statusText = findViewById(R.id.statusText)
        subtitleText = findViewById(R.id.subtitleText)
        errorCard = findViewById(R.id.errorCard)
        errorText = findViewById(R.id.errorText)
        ring1 = findViewById(R.id.ring1)
        ring2 = findViewById(R.id.ring2)
        ring3 = findViewById(R.id.ring3)

        subtitleText.text = "Find '$deviceName' on your Mac → Control Centre → Screen Mirroring"

        startPulse()
        surfaceView.holder.addCallback(this)
    }

    // ── Animations ────────────────────────────────────────────────────────────

    private fun startPulse() {
        pulseAnimator?.cancel()

        fun ringAnim(ring: View, startDelay: Long): AnimatorSet {
            fun looping(anim: ObjectAnimator) = anim.apply {
                repeatCount = ObjectAnimator.INFINITE
                repeatMode  = ObjectAnimator.RESTART
            }
            val scaleX = looping(ObjectAnimator.ofFloat(ring, "scaleX", 0.6f, 1.4f))
            val scaleY = looping(ObjectAnimator.ofFloat(ring, "scaleY", 0.6f, 1.4f))
            val alpha  = looping(ObjectAnimator.ofFloat(ring, "alpha", 0.7f, 0f))
            return AnimatorSet().apply {
                playTogether(scaleX, scaleY, alpha)
                duration = 2400
                this.startDelay = startDelay
                interpolator = AccelerateDecelerateInterpolator()
            }
        }

        pulseAnimator = AnimatorSet().apply {
            playTogether(
                ringAnim(ring1, 0L),
                ringAnim(ring2, 600L),
                ringAnim(ring3, 1200L)
            )
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        listOf(ring1, ring2, ring3).forEach { it.alpha = 0f }
    }

    private fun showConnected() {
        runOnUiThread {
            stopPulse()
            errorCard.visibility = View.GONE
            overlay.animate().alpha(0f).setDuration(400).withEndAction {
                overlay.visibility = View.GONE
            }.start()
        }
    }

    private fun showWaiting() {
        clearSurface()
        runOnUiThread {
            errorCard.visibility = View.GONE
            statusText.text = "Waiting for AirPlay…"
            overlay.visibility = View.VISIBLE
            overlay.alpha = 0f
            overlay.animate().alpha(1f).setDuration(400).start()
            startPulse()
        }
    }

    private fun clearSurface() {
        try {
            val canvas = surfaceView.holder.lockCanvas() ?: return
            canvas.drawColor(android.graphics.Color.BLACK)
            surfaceView.holder.unlockCanvasAndPost(canvas)
        } catch (_: Exception) {}
    }

    private fun showError(message: String) {
        runOnUiThread {
            errorCard.visibility = View.VISIBLE
            errorText.text = message
        }
    }

    // ── SurfaceHolder.Callback ────────────────────────────────────────────────

    override fun surfaceCreated(holder: SurfaceHolder) {
        decoder = AirPlayDecoder(holder.surface) { w, h -> onVideoDimensions(w, h) }
        audioPlayer = AirPlayAudioPlayer()
        startAirPlay()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        applyAspectRatio()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopAirPlay()
        decoder?.release()
        decoder = null
        audioPlayer?.release()
        audioPlayer = null
    }

    // ── AirPlayCallback (called from JNI threads) ─────────────────────────────

    override fun onVideoData(data: ByteArray, isH265: Boolean) {
        val d = decoder
        if (d == null) { Log.w(TAG, "onVideoData: decoder is null"); return }
        d.onVideoData(data, isH265)
    }

    override fun onAudioData(data: ByteArray, ct: Int) {
        if (data.size > 0 && Math.random() < 0.01) Log.d(TAG, "onAudioData received ${data.size} bytes, ct=$ct")
        audioPlayer?.onAudioData(data, ct)
    }

    override fun onConnected() {
        Log.i(TAG, "AirPlay client connected")
        showConnected()
    }

    override fun onDisconnected() {
        Log.i(TAG, "AirPlay client disconnected")
        showWaiting()
    }

    // ── Server lifecycle ──────────────────────────────────────────────────────

    private fun startAirPlay() {
        val hwAddr = getWifiMac()
        Log.i(TAG, "Starting AirPlay: name=$deviceName, mac=$hwAddr, port=$serverPort")

        val ok = AirPlayBridge.nativeStart(serverPort, deviceName, hwAddr, this)
        if (!ok) {
            Log.e(TAG, "Failed to start native AirPlay server")
            showError("Failed to start AirPlay server")
            return
        }

        nsdService = AirPlayNsdService(this, deviceName, hwAddr, AirPlayBridge.nativeGetPort())
        nsdService?.register()
    }

    private fun stopAirPlay() {
        nsdService?.unregister()
        nsdService = null
        AirPlayBridge.nativeStop()
    }

    private fun getWifiMac(): String {
        return try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wm.connectionInfo.macAddress.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }
                ?: "AA:BB:CC:DD:EE:FF"
        } catch (e: Exception) {
            "AA:BB:CC:DD:EE:FF"
        }
    }

    // ── Aspect ratio ──────────────────────────────────────────────────────────

    /** Called by the decoder when it learns the real video dimensions. */
    fun onVideoDimensions(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        videoWidth = width
        videoHeight = height
        runOnUiThread { applyAspectRatio() }
    }

    /**
     * Resize [surfaceContainer] so it fits inside the parent while preserving
     * the video aspect ratio. Black bars appear on the sides (letterbox/pillarbox).
     */
    private fun applyAspectRatio() {
        val parent = surfaceContainer.parent as? View ?: return
        val parentW = parent.width.takeIf { it > 0 } ?: return
        val parentH = parent.height.takeIf { it > 0 } ?: return

        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val parentAspect = parentW.toFloat() / parentH.toFloat()

        val (newW, newH) = if (videoAspect >= parentAspect) {
            // Video is wider → fit width, bars top/bottom
            parentW to (parentW / videoAspect).toInt()
        } else {
            // Video is taller → fit height, bars left/right
            (parentH * videoAspect).toInt() to parentH
        }

        val lp = surfaceContainer.layoutParams as FrameLayout.LayoutParams
        lp.width = newW
        lp.height = newH
        surfaceContainer.layoutParams = lp
        Log.d(TAG, "Aspect ratio applied: ${newW}x${newH} for video ${videoWidth}x${videoHeight}")
    }

    override fun onDestroy() {
        stopPulse()
        stopAirPlay()
        super.onDestroy()
    }

    companion object { private const val TAG = "MainActivity" }
}

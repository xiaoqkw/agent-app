package com.example.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AgentAccessibilityService : AccessibilityService(), AgentWebServer.LabelProvider, AgentWebServer.ActionExecutor {

    private val labelsRef: AtomicReference<List<UiLabel>> = AtomicReference(emptyList())
    private val labelManager = LabelManager()
    private val hasPendingRefresh = AtomicBoolean(false)

    private var overlayView: LabelOverlayView? = null
    private var windowManager: WindowManager? = null
    private var server: AgentWebServer? = null
    private var handlerThread: HandlerThread? = null
    private var worker: Handler? = null
    private var activePort: Int? = null

    private var serverPort = DEFAULT_PORT
    private var token: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        handlerThread = HandlerThread("agent-worker").also {
            it.start()
            worker = Handler(it.looper)
        }
        setupOverlay()
        startHttpServer()
        startForegroundSafe()
        scheduleRefresh()
        Log.i(TAG, "Service connected, listening on $serverPort")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        readConfig(intent)
        restartServerIfNeeded()
        scheduleRefresh()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // Debounce heavy work: only mark dirty and refresh on worker.
        scheduleRefresh()
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        overlayView?.let { windowManager?.removeView(it) }
        worker?.removeCallbacksAndMessages(null)
        handlerThread?.quitSafely()
        try {
            handlerThread?.join(500)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Worker thread join interrupted", e)
        }
        overlayView = null
    }

    // LabelProvider
    override fun labels(): List<UiLabel> = labelsRef.get()

    // ActionExecutor
    override fun click(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun scheduleRefresh() {
        if (!hasPendingRefresh.compareAndSet(false, true)) return
        worker?.post {
            try {
                refreshLabels()
            } finally {
                hasPendingRefresh.set(false)
            }
        }
    }

    private fun refreshLabels() {
        val root: AccessibilityNodeInfo = rootInActiveWindow ?: return
        try {
            val newLabels = labelManager.buildLabels(root)
            labelsRef.set(newLabels)
            overlayView?.update(newLabels)
            Log.d(TAG, "refresh: ${newLabels.size} labels")
        } finally {
            root.recycle()
        }
    }

    private fun setupOverlay() {
        val overlay = LabelOverlayView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        windowManager?.addView(overlay, lp)
        overlayView = overlay
    }

    private fun readConfig(intent: Intent?) {
        if (intent == null) return
        val newPort = intent.getIntExtra(EXTRA_PORT, serverPort)
        val newToken = intent.getStringExtra(EXTRA_TOKEN)
        serverPort = newPort
        token = newToken ?: token
    }

    private fun startHttpServer() {
        stopHttpServer()
        server = AgentWebServer(
            host = "127.0.0.1",
            port = serverPort,
            token = token,
            provider = this,
            actionExecutor = this,
            refresher = { scheduleRefresh() }
        ).also {
            try {
                it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                activePort = serverPort
            } catch (e: IOException) {
                Log.e(TAG, "Server start failed on $serverPort", e)
                throw e
            }
        }
    }

    private fun restartServerIfNeeded() {
        if (activePort != serverPort) {
            startHttpServer()
        }
    }

    private fun stopHttpServer() {
        try {
            server?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping server", e)
        }
    }

    private fun startForegroundSafe() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channelId = "agent_overlay"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(
                channelId,
                "Agent Overlay",
                NotificationManager.IMPORTANCE_MIN
            )
            nm.createNotificationChannel(channel)
        }
        val notification: Notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("GUI Agent running")
            .setContentText("Overlay + HTTP server active")
            .build()
        startForeground(1, notification)
    }

    companion object {
        private const val TAG = "AgentService"
        const val EXTRA_PORT = "PORT"
        const val EXTRA_TOKEN = "TOKEN"
        private const val DEFAULT_PORT = 8080
    }
}

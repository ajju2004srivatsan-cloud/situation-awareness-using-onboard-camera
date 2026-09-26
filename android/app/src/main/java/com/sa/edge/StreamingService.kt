package com.sa.edge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.rtmp.RtmpDisplay
import com.pedro.library.view.OpenGlView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class StreamingService : Service(), ConnectChecker {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var telemetryJob: Job? = null
    private var hudJob: Job? = null
    private var config: EdgeConfig? = null
    private var mode: ModeDetector.Mode = ModeDetector.Mode.PHONE

    private var rtmpCamera: RtmpCamera2? = null
    private var rtmpDisplay: RtmpDisplay? = null
    private var telemetry: TelemetryClient? = null
    private var phoneSensors: PhoneSensors? = null
    private var mavlink: MavlinkUdpReader? = null
    @Volatile private var rtmpOk = false
    @Volatile private var wsOk = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreaming()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val cfg = EdgeConfig(
                    serverHost = intent.getStringExtra(EXTRA_HOST) ?: "192.168.0.137",
                    deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: EdgeConfig.defaultDeviceId(),
                    modePref = intent.getStringExtra(EXTRA_MODE) ?: EdgeConfig.defaultMode(),
                    rtspUrl = intent.getStringExtra(EXTRA_RTSP) ?: "rtsp://192.168.144.108:554/stream=0",
                    mavlinkPort = intent.getIntExtra(EXTRA_MAV_PORT, 14550),
                    publicBaseUrl = intent.getStringExtra(EXTRA_PUBLIC_BASE) ?: "",
                    rtmpPublic = intent.getStringExtra(EXTRA_RTMP_PUBLIC) ?: "",
                )
                config = cfg
                mode = ModeDetector.resolve(cfg.modePref)
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                startForegroundTyped(cfg.deviceId)
                startStreaming(cfg, resultCode, data)
            }
        }
        return START_STICKY
    }

    private fun startForegroundTyped(deviceId: String) {
        val channelId = "sa_stream"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "SA Streaming", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("SA G20 live")
            .setContentText("Publishing $deviceId")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(42, notification, types)
        } else {
            startForeground(42, notification)
        }
    }

    private fun startStreaming(cfg: EdgeConfig, resultCode: Int, data: Intent?) {
        stopStreaming(keepService = true)
        rtmpOk = false
        wsOk = false

        telemetry = TelemetryClient(cfg.wsUrl) { connected ->
            wsOk = connected
            broadcastStatus(if (connected) "Telemetry WS connected" else "Telemetry WS reconnecting…")
        }.also { it.connect() }

        when (mode) {
            ModeDetector.Mode.PHONE -> startPhone(cfg)
            ModeDetector.Mode.G20 -> startG20(cfg, resultCode, data)
        }

        telemetryJob = scope.launch {
            while (isActive) {
                publishTelemetry(cfg)
                delay(1000)
            }
        }

        hudJob = scope.launch {
            while (isActive) {
                broadcastHud()
                delay(500)
            }
        }

        broadcastStatus("Live pipeline starting for ${cfg.deviceId}")
    }

    private fun startPhone(cfg: EdgeConfig) {
        phoneSensors = PhoneSensors(this).also { it.start() }
        val view = PreviewHolder.openGlView
        val camera = if (view != null) RtmpCamera2(view, this) else RtmpCamera2(this, true, this)
        rtmpCamera = camera
        val audioOk = runCatching { camera.prepareAudio() }.getOrDefault(false)
        if (!camera.prepareVideo(1280, 720, 30, 2_500_000, 90)) {
            broadcastStatus("Camera prepare failed")
            return
        }
        if (!audioOk) Log.w(TAG, "Audio prepare failed — streaming video only")
        camera.startStream(cfg.rtmpUrl)
    }

    private fun startG20(cfg: EdgeConfig, resultCode: Int, data: Intent?) {
        mavlink = MavlinkUdpReader(cfg.mavlinkPort, scope).also { it.start() }
        if (data == null) {
            broadcastStatus("Allow screen capture to republish the drone video")
            return
        }
        val display = RtmpDisplay(this, true, this)
        rtmpDisplay = display
        val w = resources.displayMetrics.widthPixels.coerceIn(640, 1280)
        val h = resources.displayMetrics.heightPixels.coerceIn(360, 720)
        // Audio is optional on G20 (screen capture of RTSP)
        runCatching { display.prepareAudio() }
        if (!display.prepareVideo(w, h, 25, 2_000_000, 0)) {
            broadcastStatus("Display encoder prepare failed")
            return
        }
        display.setIntentResult(resultCode, data)
        display.startStream(cfg.rtmpUrl)
        broadcastStatus("RTMP → ${cfg.rtmpUrl}")
    }

    private fun publishTelemetry(cfg: EdgeConfig) {
        val client = telemetry ?: return
        when (mode) {
            ModeDetector.Mode.PHONE -> {
                val s = phoneSensors ?: return
                if (!s.hasFix) return
                client.send(cfg.deviceId, cfg.type, s.lat, s.lng, s.altitude, s.heading, cfg.streamUrl)
            }
            ModeDetector.Mode.G20 -> {
                val m = mavlink ?: return
                if (!m.hasFix) return
                client.send(cfg.deviceId, cfg.type, m.lat, m.lng, m.altitude, m.heading, cfg.streamUrl)
            }
        }
    }

    private fun broadcastHud() {
        val m = mavlink
        val intent = Intent(ACTION_HUD).apply {
            putExtra(EXTRA_RTMP_OK, rtmpOk)
            putExtra(EXTRA_WS_OK, wsOk)
            putExtra(EXTRA_MAV_ALIVE, m?.isAlive == true)
            putExtra(EXTRA_MAV_FIX, m?.hasFix == true)
            putExtra(EXTRA_MAV_PACKETS, m?.packetsReceived?.get() ?: 0L)
            putExtra(EXTRA_LAT, m?.lat ?: 0.0)
            putExtra(EXTRA_LNG, m?.lng ?: 0.0)
            putExtra(EXTRA_ALT, m?.altitude ?: 0.0)
            putExtra(EXTRA_HDG, m?.heading ?: 0.0)
        }
        sendBroadcast(intent)
    }

    private fun broadcastStatus(msg: String) {
        sendBroadcast(Intent(ACTION_STATUS).putExtra(EXTRA_STATUS, msg))
    }

    private fun stopStreaming(keepService: Boolean = false) {
        telemetryJob?.cancel()
        telemetryJob = null
        hudJob?.cancel()
        hudJob = null
        runCatching { rtmpCamera?.stopStream() }
        runCatching { rtmpCamera?.stopPreview() }
        rtmpCamera = null
        runCatching { rtmpDisplay?.stopStream() }
        rtmpDisplay = null
        phoneSensors?.stop()
        phoneSensors = null
        mavlink?.stop()
        mavlink = null
        telemetry?.close()
        telemetry = null
        rtmpOk = false
        wsOk = false
        if (!keepService) broadcastStatus("Stopped")
    }

    override fun onDestroy() {
        stopStreaming()
        scope.cancel()
        super.onDestroy()
    }

    override fun onConnectionStarted(url: String) {
        Log.i(TAG, "connection started $url")
        broadcastStatus("Connecting RTMP…")
    }

    override fun onConnectionSuccess() {
        rtmpOk = true
        broadcastStatus("RTMP connected")
    }

    override fun onConnectionFailed(reason: String) {
        rtmpOk = false
        broadcastStatus("RTMP failed: $reason")
    }

    override fun onNewBitrate(bitrate: Long) = Unit
    override fun onDisconnect() {
        rtmpOk = false
        broadcastStatus("RTMP disconnected")
    }
    override fun onAuthError() = Unit
    override fun onAuthSuccess() = Unit

    companion object {
        private const val TAG = "StreamingService"
        const val ACTION_START = "com.sa.edge.START"
        const val ACTION_STOP = "com.sa.edge.STOP"
        const val ACTION_STATUS = "com.sa.edge.STATUS"
        const val ACTION_HUD = "com.sa.edge.HUD"
        const val EXTRA_HOST = "host"
        const val EXTRA_DEVICE_ID = "deviceId"
        const val EXTRA_MODE = "mode"
        const val EXTRA_RTSP = "rtsp"
        const val EXTRA_MAV_PORT = "mavPort"
        const val EXTRA_PUBLIC_BASE = "publicBase"
        const val EXTRA_RTMP_PUBLIC = "rtmpPublic"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_STATUS = "status"
        const val EXTRA_RTMP_OK = "rtmpOk"
        const val EXTRA_WS_OK = "wsOk"
        const val EXTRA_MAV_ALIVE = "mavAlive"
        const val EXTRA_MAV_FIX = "mavFix"
        const val EXTRA_MAV_PACKETS = "mavPackets"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_ALT = "alt"
        const val EXTRA_HDG = "hdg"

        fun stop(context: Context) {
            context.startService(Intent(context, StreamingService::class.java).setAction(ACTION_STOP))
        }
    }
}

object PreviewHolder {
    @Volatile var openGlView: OpenGlView? = null
}

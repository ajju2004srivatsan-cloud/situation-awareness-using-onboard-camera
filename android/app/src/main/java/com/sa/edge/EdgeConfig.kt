package com.sa.edge

import android.content.Context
import android.content.SharedPreferences

data class EdgeConfig(
    val serverHost: String,
    val deviceId: String,
    val modePref: String,
    val rtspUrl: String,
    val mavlinkPort: Int,
    /** Cloudflare HTTPS URL, e.g. https://xxxx.trycloudflare.com */
    val publicBaseUrl: String = "",
    /** Public RTMP base from bore, e.g. rtmp://bore.pub:12345 */
    val rtmpPublic: String = "",
) {
    val rtmpUrl: String
        get() {
            val r = rtmpPublic.trim().trimEnd('/')
            return when {
                r.startsWith("rtmp://") && r.contains("/live/") -> r
                r.startsWith("rtmp://") -> "$r/live/$deviceId"
                r.contains(":") -> "rtmp://$r/live/$deviceId"
                else -> "rtmp://$serverHost:1935/live/$deviceId"
            }
        }

    val wsUrl: String
        get() {
            val pub = publicBaseUrl.trim().trimEnd('/')
            return when {
                pub.startsWith("https://") -> "wss://${pub.removePrefix("https://")}/ws?role=edge"
                pub.startsWith("http://") -> "ws://${pub.removePrefix("http://")}/ws?role=edge"
                else -> "ws://$serverHost:8080/ws?role=edge"
            }
        }

    val streamUrl: String
        get() {
            val pub = publicBaseUrl.trim().trimEnd('/')
            return if (pub.isNotEmpty()) {
                "$pub/hls/live/$deviceId/index.m3u8"
            } else {
                "webrtc://$serverHost:8889/live/$deviceId"
            }
        }

    val type: String
        get() = when (ModeDetector.resolve(modePref)) {
            ModeDetector.Mode.G20 -> "uav"
            ModeDetector.Mode.PHONE -> "ground"
        }

    fun save(prefs: SharedPreferences) {
        prefs.edit()
            .putString(KEY_HOST, serverHost)
            .putString(KEY_DEVICE, deviceId)
            .putString(KEY_MODE, modePref)
            .putString(KEY_RTSP, rtspUrl)
            .putInt(KEY_MAV, mavlinkPort)
            .putString(KEY_PUBLIC, publicBaseUrl)
            .putString(KEY_RTMP_PUBLIC, rtmpPublic)
            .apply()
    }

    companion object {
        private const val KEY_HOST = "server_host"
        private const val KEY_DEVICE = "device_id"
        private const val KEY_MODE = "mode_pref"
        private const val KEY_RTSP = "rtsp_url"
        private const val KEY_MAV = "mavlink_port"
        private const val KEY_PUBLIC = "public_base_url"
        private const val KEY_RTMP_PUBLIC = "rtmp_public"

        fun defaultDeviceId(): String =
            if (ModeDetector.isLikelyG20()) "drone_g20_01" else "ground_phone_01"

        fun defaultMode(): String =
            if (ModeDetector.isLikelyG20()) "g20" else "auto"

        fun load(context: Context): EdgeConfig {
            val prefs = context.getSharedPreferences("sa_edge", Context.MODE_PRIVATE)
            return EdgeConfig(
                serverHost = prefs.getString(KEY_HOST, BuildConfig.DEFAULT_SERVER_HOST)
                    ?: BuildConfig.DEFAULT_SERVER_HOST,
                deviceId = prefs.getString(KEY_DEVICE, defaultDeviceId()) ?: defaultDeviceId(),
                modePref = prefs.getString(KEY_MODE, defaultMode()) ?: defaultMode(),
                rtspUrl = prefs.getString(KEY_RTSP, "rtsp://192.168.144.108:554/stream=0")
                    ?: "rtsp://192.168.144.108:554/stream=0",
                mavlinkPort = prefs.getInt(KEY_MAV, 14550),
                publicBaseUrl = prefs.getString(KEY_PUBLIC, "") ?: "",
                rtmpPublic = prefs.getString(KEY_RTMP_PUBLIC, "") ?: "",
            )
        }
    }
}

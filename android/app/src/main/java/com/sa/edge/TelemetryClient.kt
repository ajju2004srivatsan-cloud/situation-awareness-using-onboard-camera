package com.sa.edge

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class TelemetryClient(
    private val wsUrl: String,
    private val onState: ((Boolean) -> Unit)? = null,
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val socket = AtomicReference<WebSocket?>(null)
    private val open = AtomicBoolean(false)
    private var closedByUser = false

    val isConnected: Boolean get() = open.get()

    fun connect() {
        closedByUser = false
        if (socket.get() != null) return
        openSocket()
    }

    private fun openSocket() {
        val req = Request.Builder().url(wsUrl).build()
        socket.set(client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open.set(true)
                onState?.invoke(true)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                open.set(false)
                socket.compareAndSet(webSocket, null)
                onState?.invoke(false)
                if (!closedByUser) {
                    // simple reconnect
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (!closedByUser && socket.get() == null) openSocket()
                    }, 2000)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                open.set(false)
                socket.compareAndSet(webSocket, null)
                onState?.invoke(false)
                if (!closedByUser) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (!closedByUser && socket.get() == null) openSocket()
                    }, 2000)
                }
            }
        }))
    }

    fun send(
        deviceId: String,
        type: String,
        lat: Double,
        lng: Double,
        altitude: Double,
        heading: Double,
        streamUrl: String,
    ) {
        val payload = JSONObject()
            .put("deviceId", deviceId)
            .put("type", type)
            .put("lat", lat)
            .put("lng", lng)
            .put("altitude", altitude)
            .put("heading", heading)
            .put("streamUrl", streamUrl)
            .toString()
        socket.get()?.send(payload)
    }

    fun close() {
        closedByUser = true
        open.set(false)
        socket.getAndSet(null)?.close(1000, "bye")
        client.dispatcher.executorService.shutdown()
    }
}

package com.sa.edge

import io.dronefleet.mavlink.MavlinkConnection
import io.dronefleet.mavlink.common.Attitude
import io.dronefleet.mavlink.common.GlobalPositionInt
import io.dronefleet.mavlink.common.Heartbeat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.toDegrees

/**
 * MAVLink UDP listener for Skydroid G20.
 *
 * On the G20 / companion computer, forward MAVLink with MAVProxy:
 *   output udp 127.0.0.1:14550
 * or from another host:
 *   output udp <g20-ip>:14550
 */
class MavlinkUdpReader(
    private val port: Int,
    private val scope: CoroutineScope,
) {
    @Volatile var lat: Double = 0.0
    @Volatile var lng: Double = 0.0
    @Volatile var altitudeMsl: Double = 0.0
    @Volatile var altitudeRel: Double = 0.0
    @Volatile var heading: Double = 0.0
    @Volatile var hasFix: Boolean = false
    @Volatile var lastPacketAt: Long = 0L
    @Volatile var lastHeartbeatAt: Long = 0L

    val packetsReceived = AtomicLong(0)
    val messagesParsed = AtomicLong(0)

    /** Prefer relative AGL when available, else MSL. */
    val altitude: Double
        get() = if (altitudeRel != 0.0) altitudeRel else altitudeMsl

    val isAlive: Boolean
        get() = System.currentTimeMillis() - lastPacketAt < 3000

    private var job: Job? = null
    private var socket: DatagramSocket? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val sock = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
                soTimeout = 0
            }
            socket = sock

            val pipeIn = PipedInputStream(256 * 1024)
            val pipeOut = PipedOutputStream(pipeIn)

            val parser = launch(Dispatchers.IO) {
                try {
                    val connection = MavlinkConnection.create(pipeIn, NullOutput())
                    while (isActive) {
                        try {
                            val message = connection.next() ?: continue
                            messagesParsed.incrementAndGet()
                            when (val payload = message.payload) {
                                is GlobalPositionInt -> {
                                    lat = payload.lat() / 1e7
                                    lng = payload.lon() / 1e7
                                    altitudeMsl = payload.alt() / 1000.0
                                    altitudeRel = payload.relativeAlt() / 1000.0
                                    val hdg = payload.hdg()
                                    if (hdg in 0..35999) heading = hdg / 100.0
                                    if (payload.lat() != 0 || payload.lon() != 0) hasFix = true
                                }
                                is Attitude -> {
                                    var yawDeg = toDegrees(payload.yaw().toDouble())
                                    if (yawDeg < 0) yawDeg += 360.0
                                    heading = yawDeg
                                }
                                is Heartbeat -> {
                                    lastHeartbeatAt = System.currentTimeMillis()
                                }
                            }
                        } catch (_: Exception) {
                            // keep listening — truncated frames happen on UDP
                        }
                    }
                } catch (_: Exception) {
                }
            }

            val buf = ByteArray(4096)
            try {
                while (isActive) {
                    val packet = DatagramPacket(buf, buf.size)
                    sock.receive(packet)
                    packetsReceived.incrementAndGet()
                    lastPacketAt = System.currentTimeMillis()
                    try {
                        pipeOut.write(packet.data, packet.offset, packet.length)
                        pipeOut.flush()
                    } catch (_: Exception) {
                        break
                    }
                }
            } finally {
                parser.cancel()
                runCatching { pipeOut.close() }
                runCatching { pipeIn.close() }
                runCatching { sock.close() }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
    }

    /** Discarding output stream for MavlinkConnection writer side. */
    private class NullOutput : java.io.OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }
}

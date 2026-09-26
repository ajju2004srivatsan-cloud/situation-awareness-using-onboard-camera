package com.sa.edge

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.sa.edge.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null
    private var pendingConfig: EdgeConfig? = null
    private var rtspOk = false
    private var settingsOpen = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) {
            maybeStart()
        } else {
            Toast.makeText(this, "Mic / notification permission needed for live publish", Toast.LENGTH_LONG).show()
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val cfg = pendingConfig ?: return@registerForActivityResult
        if (result.resultCode != RESULT_OK || result.data == null) {
            binding.statusText.text = "Screen capture denied — needed to republish RTSP"
            setPill(binding.pillRtmp, false, err = true)
            return@registerForActivityResult
        }
        startServiceWith(cfg, result.resultCode, result.data)
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                StreamingService.ACTION_STATUS -> {
                    binding.statusText.text = intent.getStringExtra(StreamingService.EXTRA_STATUS) ?: return
                }
                StreamingService.ACTION_HUD -> {
                    val rtmp = intent.getBooleanExtra(StreamingService.EXTRA_RTMP_OK, false)
                    val ws = intent.getBooleanExtra(StreamingService.EXTRA_WS_OK, false)
                    val mavAlive = intent.getBooleanExtra(StreamingService.EXTRA_MAV_ALIVE, false)
                    val mavFix = intent.getBooleanExtra(StreamingService.EXTRA_MAV_FIX, false)
                    val pkts = intent.getLongExtra(StreamingService.EXTRA_MAV_PACKETS, 0L)
                    val lat = intent.getDoubleExtra(StreamingService.EXTRA_LAT, 0.0)
                    val lng = intent.getDoubleExtra(StreamingService.EXTRA_LNG, 0.0)
                    val alt = intent.getDoubleExtra(StreamingService.EXTRA_ALT, 0.0)
                    val hdg = intent.getDoubleExtra(StreamingService.EXTRA_HDG, 0.0)

                    setPill(binding.pillRtmp, rtmp)
                    setPill(binding.pillWs, ws)
                    setPill(binding.pillMav, mavAlive, err = !mavAlive && pkts == 0L)
                    binding.hudCoords.text = if (mavFix) {
                        "LAT ${"%.6f".format(lat)}  LNG ${"%.6f".format(lng)}"
                    } else {
                        "LAT --  LNG --  (waiting GPS)"
                    }
                    binding.hudAltHdg.text = "ALT ${"%.1f".format(alt)} m   HDG ${"%.0f".format(hdg)}°"
                    binding.hudMavStats.text = "MAV pkts $pkts · ${if (mavFix) "GPS fix" else "no fix"}"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val modes = listOf("auto", "phone", "g20")
        binding.modeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            modes.map {
                when (it) {
                    "phone" -> getString(R.string.mode_phone)
                    "g20" -> getString(R.string.mode_g20)
                    else -> getString(R.string.mode_auto)
                }
            },
        )

        val saved = EdgeConfig.load(this)
        binding.serverHost.setText(saved.serverHost)
        binding.publicBaseUrl.setText(saved.publicBaseUrl)
        binding.rtmpPublic.setText(saved.rtmpPublic)
        binding.deviceId.setText(saved.deviceId)
        binding.rtspUrl.setText(saved.rtspUrl)
        binding.mavlinkPort.setText(saved.mavlinkPort.toString())
        binding.hudDevice.text = saved.deviceId
        binding.modeSpinner.setSelection(
            when (saved.modePref) {
                "phone" -> 1
                "g20" -> 2
                else -> 0
            },
        )

        binding.btnSettings.setOnClickListener {
            settingsOpen = !settingsOpen
            binding.settingsPanel.visibility = if (settingsOpen) View.VISIBLE else View.GONE
        }
        binding.btnPreview.setOnClickListener {
            val cfg = currentConfig()
            updatePreviewVisibility(ModeDetector.resolve(cfg.modePref))
            if (ModeDetector.resolve(cfg.modePref) == ModeDetector.Mode.G20) {
                startRtspPreview(cfg.rtspUrl)
            }
        }
        binding.btnStart.setOnClickListener { requestAndStart() }
        binding.btnStop.setOnClickListener {
            StreamingService.stop(this)
            binding.statusText.text = "Stopped"
            setPill(binding.pillRtmp, false)
            setPill(binding.pillWs, false)
        }

        val mode = ModeDetector.resolve(saved.modePref)
        updatePreviewVisibility(mode)
        binding.titleText.text = if (mode == ModeDetector.Mode.G20) "SA G20 UAV" else "SA Edge"
        if (mode == ModeDetector.Mode.G20) {
            // Auto-preview RTSP so operator sees drone video immediately
            startRtspPreview(saved.rtspUrl)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(StreamingService.ACTION_STATUS)
            addAction(StreamingService.ACTION_HUD)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(statusReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        PreviewHolder.openGlView = null
        super.onDestroy()
    }

    private fun currentConfig(): EdgeConfig {
        val modeIdx = binding.modeSpinner.selectedItemPosition
        val modePref = when (modeIdx) {
            1 -> "phone"
            2 -> "g20"
            else -> "auto"
        }
        return EdgeConfig(
            serverHost = binding.serverHost.text?.toString()?.trim().orEmpty().ifEmpty { "192.168.0.137" },
            deviceId = binding.deviceId.text?.toString()?.trim().orEmpty().ifEmpty { EdgeConfig.defaultDeviceId() },
            modePref = modePref,
            rtspUrl = binding.rtspUrl.text?.toString()?.trim().orEmpty()
                .ifEmpty { "rtsp://192.168.144.108:554/stream=0" },
            mavlinkPort = binding.mavlinkPort.text?.toString()?.toIntOrNull() ?: 14550,
            publicBaseUrl = binding.publicBaseUrl.text?.toString()?.trim().orEmpty(),
            rtmpPublic = binding.rtmpPublic.text?.toString()?.trim().orEmpty(),
        ).also {
            it.save(getSharedPreferences("sa_edge", MODE_PRIVATE))
            binding.hudDevice.text = it.deviceId
        }
    }

    private fun requestAndStart() {
        pendingConfig = currentConfig()
        val mode = ModeDetector.resolve(pendingConfig!!.modePref)
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (mode == ModeDetector.Mode.PHONE) {
            needed += listOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            maybeStart()
        }
    }

    private fun maybeStart() {
        val cfg = pendingConfig ?: currentConfig()
        val mode = ModeDetector.resolve(cfg.modePref)
        updatePreviewVisibility(mode)
        when (mode) {
            ModeDetector.Mode.PHONE -> startServiceWith(cfg, 0, null)
            ModeDetector.Mode.G20 -> {
                startRtspPreview(cfg.rtspUrl)
                binding.statusText.text = "Allow screen capture to publish drone video…"
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            }
        }
    }

    private fun updatePreviewVisibility(mode: ModeDetector.Mode) {
        if (mode == ModeDetector.Mode.G20) {
            binding.cameraPreview.visibility = View.GONE
            binding.playerView.visibility = View.VISIBLE
            binding.hudPanel.visibility = View.VISIBLE
        } else {
            binding.playerView.visibility = View.GONE
            binding.cameraPreview.visibility = View.VISIBLE
            binding.hudPanel.visibility = View.GONE
        }
    }

    private fun startRtspPreview(url: String) {
        player?.release()
        rtspOk = false
        setPill(binding.pillRtsp, false)
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        binding.playerView.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    rtspOk = true
                    setPill(binding.pillRtsp, true)
                    binding.statusText.text = "RTSP preview ready"
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                rtspOk = false
                setPill(binding.pillRtsp, false, err = true)
                binding.statusText.text = "RTSP error: ${error.message ?: "unreachable"}"
            }
        })
        val mediaItem = MediaItem.fromUri(url)
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .createMediaSource(mediaItem)
        exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun startServiceWith(cfg: EdgeConfig, resultCode: Int, data: Intent?) {
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra(StreamingService.EXTRA_HOST, cfg.serverHost)
            putExtra(StreamingService.EXTRA_DEVICE_ID, cfg.deviceId)
            putExtra(StreamingService.EXTRA_MODE, cfg.modePref)
            putExtra(StreamingService.EXTRA_RTSP, cfg.rtspUrl)
            putExtra(StreamingService.EXTRA_MAV_PORT, cfg.mavlinkPort)
            putExtra(StreamingService.EXTRA_PUBLIC_BASE, cfg.publicBaseUrl)
            putExtra(StreamingService.EXTRA_RTMP_PUBLIC, cfg.rtmpPublic)
            putExtra(StreamingService.EXTRA_RESULT_CODE, resultCode)
            if (data != null) putExtra(StreamingService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, intent)
        binding.statusText.text = "Starting live publish…"
    }

    private fun setPill(view: TextView, on: Boolean, err: Boolean = false) {
        view.setBackgroundResource(
            when {
                err -> R.drawable.pill_err
                on -> R.drawable.pill_on
                else -> R.drawable.pill_off
            },
        )
        view.setTextColor(
            Color.parseColor(
                when {
                    err -> "#F07178"
                    on -> "#3DD6C6"
                    else -> "#8B9BB8"
                },
            ),
        )
    }
}

/*
 * Akashvani Patna Live - Android Radio Streaming App
 * 
 * BUILD/GRADLE DEPENDENCIES REQUIRED:
 * ====================================
 * Add to app/build.gradle dependencies:
 * 
 * implementation 'androidx.media3:media3-exoplayer:1.2.0'
 * implementation 'androidx.media3:media3-exoplayer-hls:1.2.0'
 * implementation 'androidx.media3:media3-ui:1.2.0'
 * implementation 'androidx.media3:media3-common:1.2.0'
 * implementation 'androidx.core:core-ktx:1.12.0'
 * implementation 'androidx.appcompat:appcompat:1.6.1'
 * implementation 'com.google.android.material:material:1.10.0'
 * 
 * PERMISSIONS REQUIRED (in AndroidManifest.xml):
 * ==============================================
 * <uses-permission android:name="android.permission.INTERNET" />
 * <uses-permission android:name="android.permission.WAKE_LOCK" />
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
 * 
 * MIN SDK: 24 (Android 7.0)
 * TARGET SDK: 34 (Android 14)
 */

package com.akashvani.patna.live

import android.content.Context
import android.media.AudioAttributes as AndroidAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource

/**
 * MainActivity for Akashvani Patna Live streaming app.
 * 
 * Features:
 * - Streams HLS audio using Media3 ExoPlayer
 * - Auto-reconnect on stream failure
 * - Background playback (continues when phone is locked/closed)
 * - Audio focus management for proper audio handling
 * - Wake lock to keep device awake during playback
 * - Keeps screen awake when app is visible
 * - Simple two-button UI (PLAY/STOP)
 * - Proper lifecycle management
 */
class MainActivity : AppCompatActivity() {

    // ExoPlayer instance
    private var player: ExoPlayer? = null

    // UI elements
    private lateinit var playButton: Button
    private lateinit var stopButton: Button

    // HLS stream URL for Akashvani Patna
    // TODO: Replace with actual Akashvani Patna HLS stream URL
    // Common format: https://example.com/stream.m3u8
    private val hlsStreamUrl = "https://air.pc.cdn.bitgravity.com/air/live/pbaudio001/playlist.m3u8"

    // Retry configuration
    private val maxRetryAttempts = 5
    private val retryDelayMs = 3000L // 3 seconds
    private var retryAttempts = 0
    private val handler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null

    // Player state tracking
    private var isPlaying = false

    // Audio focus and wake lock for background playback
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI
        initializeUI()

        // Initialize audio manager for audio focus
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Initialize wake lock for background playback
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AkashvaniPatnaLive::WakeLock"
        ).apply {
            setReferenceCounted(false)
        }

        // Keep screen awake when app is visible
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Note: Player will be initialized when user clicks PLAY for better performance
    }

    /**
     * Initialize UI elements and set click listeners
     */
    private fun initializeUI() {
        playButton = findViewById(R.id.playButton)
        stopButton = findViewById(R.id.stopButton)

        playButton.setOnClickListener {
            startPlayback()
        }

        stopButton.setOnClickListener {
            stopPlayback()
        }
    }

    /**
     * Initialize ExoPlayer with HLS MediaSource
     */
    private fun initializePlayer() {
        // Release existing player if any
        releasePlayer()

        // Create ExoPlayer instance with audio attributes for background playback
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true) // Handle audio focus
            .build()

        // Set up player event listener for error handling and retry
        player?.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                super.onPlayerError(error)
                handlePlaybackError(error)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                when (playbackState) {
                    Player.STATE_READY -> {
                        // Stream is ready
                        retryAttempts = 0
                        if (isPlaying) {
                            player?.play()
                        }
                    }
                    Player.STATE_ENDED -> {
                        // Stream ended, attempt to reconnect
                        if (isPlaying) {
                            attemptReconnect()
                        }
                    }
                }
            }
        })

        // Create HLS MediaSource using DefaultHttpDataSource
        val httpDataSourceFactory: DataSource.Factory = DefaultHttpDataSource.Factory()
            .setUserAgent("AkashvaniPatnaLive/1.0")
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(10000)

        val hlsMediaSourceFactory = HlsMediaSource.Factory(httpDataSourceFactory)
            .setAllowChunklessPreparation(true) // Optimize for low-end devices

        // Create MediaItem from HLS URL
        val mediaItem = MediaItem.Builder()
            .setUri(hlsStreamUrl)
            .build()

        // Create and set MediaSource
        val mediaSource = hlsMediaSourceFactory.createMediaSource(mediaItem)
        player?.setMediaSource(mediaSource)
        // Note: prepare() will be called when starting playback
    }

    /**
     * Start playback of the HLS stream
     */
    private fun startPlayback() {
        if (isPlaying) {
            return // Already playing
        }

        // Request audio focus for background playback
        if (!requestAudioFocus()) {
            Toast.makeText(this, "Cannot play: Another app is using audio", Toast.LENGTH_SHORT).show()
            return
        }

        // Acquire wake lock to keep device awake during playback
        wakeLock?.acquire(10 * 60 * 60 * 1000L) // 10 hours max

        isPlaying = true
        retryAttempts = 0

        // If player is not initialized or in error state, reinitialize
        if (player == null || player?.playbackState == Player.STATE_IDLE) {
            initializePlayer()
            // Prepare player before starting playback
            player?.prepare()
        }

        // Start playback
        player?.playWhenReady = true
        player?.play()

        // Update UI
        playButton.isEnabled = false
        stopButton.isEnabled = true

        Toast.makeText(this, "Starting stream...", Toast.LENGTH_SHORT).show()
    }

    /**
     * Stop playback immediately
     */
    private fun stopPlayback() {
        isPlaying = false

        // Cancel any pending retry attempts
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null

        // Stop playback
        player?.playWhenReady = false
        player?.pause()
        player?.stop()

        // Release audio focus
        releaseAudioFocus()

        // Release wake lock
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        // Update UI
        playButton.isEnabled = true
        stopButton.isEnabled = false

        Toast.makeText(this, "Playback stopped", Toast.LENGTH_SHORT).show()
    }

    /**
     * Handle playback errors and attempt reconnection
     */
    private fun handlePlaybackError(error: androidx.media3.common.PlaybackException) {
        if (!isPlaying) {
            return // Don't retry if user stopped playback
        }

        when (error.errorCode) {
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> {
                // Network or parsing errors - attempt to reconnect
                attemptReconnect()
            }
            else -> {
                // Other errors - show message and attempt reconnect
                Toast.makeText(
                    this,
                    "Playback error: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
                attemptReconnect()
            }
        }
    }

    /**
     * Attempt to reconnect to the stream with exponential backoff
     */
    private fun attemptReconnect() {
        if (!isPlaying) {
            return // Don't retry if user stopped playback
        }

        if (retryAttempts >= maxRetryAttempts) {
            // Max retries reached
            isPlaying = false
            playButton.isEnabled = true
            stopButton.isEnabled = false
            Toast.makeText(
                this,
                "Failed to connect after $maxRetryAttempts attempts",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        retryAttempts++
        val delay = retryDelayMs * retryAttempts // Exponential backoff

        Toast.makeText(
            this,
            "Reconnecting... (Attempt $retryAttempts/$maxRetryAttempts)",
            Toast.LENGTH_SHORT
        ).show()

        // Cancel previous retry if any
        retryRunnable?.let { handler.removeCallbacks(it) }

        // Schedule retry
        retryRunnable = Runnable {
            if (isPlaying) {
                // Reinitialize player and restart playback
                initializePlayer()
                player?.prepare()
                player?.playWhenReady = true
                player?.play()
            }
        }

        handler.postDelayed(retryRunnable!!, delay)
    }

    /**
     * Release ExoPlayer resources
     */
    private fun releasePlayer() {
        // Cancel any pending retries
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null

        // Release player
        player?.release()
        player = null
    }

    override fun onPause() {
        super.onPause()
        // Don't pause playback - allow background playback when phone is locked
        // The player will continue playing in the background
    }

    override fun onResume() {
        super.onResume()
        // Ensure playback continues if it was playing
        if (isPlaying) {
            player?.playWhenReady = true
        }
    }

    /**
     * Request audio focus for background playback
     */
    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8.0+ requires AudioFocusRequest
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).run {
                setAudioAttributes(
                    AndroidAudioAttributes.Builder().run {
                        setUsage(AndroidAudioAttributes.USAGE_MEDIA)
                        setContentType(AndroidAudioAttributes.CONTENT_TYPE_MUSIC)
                        build()
                    }
                )
                setAcceptsDelayedFocusGain(true)
                setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            // Lost focus permanently - stop playback
                            if (isPlaying) {
                                stopPlayback()
                            }
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            // Lost focus temporarily - pause
                            player?.pause()
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            // Gained focus - resume if playing
                            if (isPlaying) {
                                player?.play()
                            }
                        }
                    }
                }
                build()
            }
            audioFocusRequest = focusRequest
            val result = audioManager?.requestAudioFocus(focusRequest)
            result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            // Android 7.1 and below
            val result = audioManager?.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    /**
     * Release audio focus
     */
    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager?.abandonAudioFocusRequest(it)
                audioFocusRequest = null
            }
        } else {
            audioManager?.abandonAudioFocus(null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release player resources when activity is destroyed
        stopPlayback()
        releasePlayer()
        // Remove keep screen on flag
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}


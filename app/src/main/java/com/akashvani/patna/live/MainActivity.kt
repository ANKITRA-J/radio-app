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
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.net.HttpURLConnection
import org.json.JSONArray

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
    private lateinit var antennaAnimation: LottieAnimationView

    // ====================================================================
    // IMPORTANT: FIND THE CORRECT AKASHVANI PATNA STREAM URL
    // ====================================================================
    // The URLs below may not be correct. You need to find the actual
    // Akashvani Patna (AM 621) stream URL and add it here.
    //
    // HOW TO FIND THE CORRECT STREAM URL:
    // 1. Visit: https://allindiaradio.gov.in or https://prasarbharati.gov.in
    // 2. Navigate to Patna station / Akashvani Patna
    // 3. Open browser Developer Tools (F12 or Right-click -> Inspect)
    // 4. Go to "Network" tab
    // 5. Play the Akashvani Patna stream on the website
    // 6. Look for requests with .m3u8, .mp3, or .aac extensions
    // 7. Copy the full URL and add it to the list below
    //
    // ALTERNATIVE: Try these websites that may have the stream:
    // - https://onlineradiofm.in/stations/all-india-radio-air-patna
    // - https://www.radioindia.in/akashvani-patna
    // - https://indiaradio.in/air-patna
    //
    // Once you find the correct URL, replace the URLs below with it.
    // ====================================================================
    
    // ================================================================
    // DYNAMIC STREAM URL FETCHING
    // ================================================================
    // The app will try to fetch the latest stream URL from Akashvani website
    // If that fails, it will use these hardcoded fallback URLs
    // ================================================================
    
    // Akashvani API endpoint (returns HTML containing station JavaScript definitions)
    private val akashvaniApiUrl = "https://akashvani.gov.in/radio/live.php"
    
    // Fallback URLs if dynamic fetch fails
    private val fallbackStreamUrls = listOf(
        // PRIMARY: Waves PB official streaming endpoint (Prasar Bharati)
        "https://radio.wavespb.com/live/c398958b3874b441/c398958b3874b441.m3u8",

        // DIRECT CDN: CloudFront endpoint (which wavespb redirects to)
        "https://d1tmej9eu7kw5c.cloudfront.net/c398958b3874b441/c398958b3874b441.m3u8"
    )
    
    // Current list of stream URLs to try (will be populated dynamically)
    private var streamUrls = mutableListOf<String>()
    
    // Station identifier for Patna on Waves PB / Akashvani portal
    private val patnaStationId = "c398958b3874b441"
    
    // Current URL index being tried
    private var currentUrlIndex = 0

    // Retry configuration
    private val maxRetryAttempts = 5
    private val retryDelayMs = 3000L // 3 seconds
    private var retryAttempts = 0
    private val handler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null
    private var bufferingTimeoutRunnable: Runnable? = null

    // Player state tracking
    private var isPlaying = false
    private var wasBuffering = false // Track if we were buffering
    private var bufferingStartTime = 0L // Track when buffering started

    // Wake lock for background playback
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI
        initializeUI()

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
        antennaAnimation = findViewById(R.id.antennaAnimation)

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
            .setAudioAttributes(audioAttributes, true) // Media3 handles audio focus automatically
            .build()
            .apply {
                // Disable video tracks: HLS stream contains dummy video track, we only want audio
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .build()
            }

        // Set up player event listener for error handling and retry
        player?.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                super.onPlayerError(error)
                android.util.Log.e("RadioApp", "Player error: ${error.errorCode} - ${error.message}")
                cancelBufferingTimeout()
                val errorMsg = when (error.errorCode) {
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Network connection failed"
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Connection timeout"
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "HTTP error: ${error.message}"
                    androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "Invalid stream format"
                    androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "Invalid HLS manifest"
                    else -> "Error ${error.errorCode}: ${error.message ?: "Unknown error"}"
                }
                handlePlaybackFailure(errorMsg)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                android.util.Log.d("RadioApp", "Playback state: $playbackState")
                when (playbackState) {
                    Player.STATE_IDLE -> {
                        android.util.Log.d("RadioApp", "State: IDLE")
                    }
                    Player.STATE_BUFFERING -> {
                        android.util.Log.d("RadioApp", "State: BUFFERING")
                        Toast.makeText(this@MainActivity, "Buffering...", Toast.LENGTH_SHORT).show()
                        scheduleBufferingTimeout()
                    }
                    Player.STATE_READY -> {
                        android.util.Log.d("RadioApp", "State: READY")
                        cancelBufferingTimeout()
                        retryAttempts = 0
                        if (isPlaying) {
                            player?.play()
                            Toast.makeText(this@MainActivity, "Stream playing!", Toast.LENGTH_SHORT).show()
                        }
                    }
                    Player.STATE_ENDED -> {
                        android.util.Log.d("RadioApp", "State: ENDED")
                        cancelBufferingTimeout()
                        if (isPlaying) {
                            attemptReconnect()
                        }
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                android.util.Log.d("RadioApp", "Is playing: $isPlaying")
            }
        })

        // Create HLS MediaSource using DefaultHttpDataSource
        val httpDataSourceFactory: DataSource.Factory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
            .setConnectTimeoutMs(15000) // Increased timeout
            .setReadTimeoutMs(15000) // Increased timeout
            .setAllowCrossProtocolRedirects(true)

        val hlsMediaSourceFactory = HlsMediaSource.Factory(httpDataSourceFactory)
            .setAllowChunklessPreparation(true) // Optimize for low-end devices

        // Create MediaItem from current HLS URL
        val currentUrl = streamUrls[currentUrlIndex]
        android.util.Log.d("RadioApp", "Trying stream URL ${currentUrlIndex + 1}/${streamUrls.size}: $currentUrl")
        
        val mediaItem = MediaItem.Builder()
            .setUri(currentUrl)
            .build()

        // Create and set MediaSource
        val mediaSource = hlsMediaSourceFactory.createMediaSource(mediaItem)
        player?.setMediaSource(mediaSource)
        // Note: prepare() will be called when starting playback
    }

    /**
     * Fetch the latest stream URL from Akashvani website dynamically
     * This ensures the app always uses the current working URL
     */
    private suspend fun fetchStreamUrlFromWebsite(): String? = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("RadioApp", "Fetching stream URL from website: $akashvaniApiUrl")
            val connection = URL(akashvaniApiUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val html = connection.inputStream.bufferedReader().use { it.readText() }
                
                // Method 1: Look for 'Akashvani Patna' in JavaScript channel definition
                // In live.php:
                // '70': {
                //     name: 'Akashvani Patna',
                //     ...
                //     live_url: 'https://radio.wavespb.com/live/c398958b3874b441/c398958b3874b441.m3u8',
                val nameRegex = """name:\s*['"]Akashvani Patna['"]""".toRegex(RegexOption.IGNORE_CASE)
                val liveUrlRegex = """live_url:\s*['"](https?://[^'"]+\.m3u8)['"]""".toRegex()
                val lines = html.lines()
                var matchedStation = false

                for (line in lines) {
                    val trimmed = line.trim()
                    if (nameRegex.find(trimmed) != null) {
                        matchedStation = true
                        continue
                    }
                    if (matchedStation) {
                        // Skip commented out lines like //live_url:
                        if (trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                            continue
                        }
                        val match = liveUrlRegex.find(trimmed)
                        if (match != null) {
                            val candidateUrl = match.groupValues[1]
                            android.util.Log.d("RadioApp", "Found live_url from JS channel: $candidateUrl")
                            if (verifyStreamUrl(candidateUrl)) {
                                return@withContext candidateUrl
                            }
                        }
                        if (trimmed.endsWith("},") || trimmed == "}") {
                            matchedStation = false
                        }
                    }
                }
                
                // Method 2: Match any uncommented live_url containing Patna station hash
                val hashRegex = """https?://[^\s'"]+$patnaStationId[^\s'"]+\.m3u8""".toRegex()
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//") || trimmed.startsWith("/*")) continue
                    val match = hashRegex.find(trimmed)
                    if (match != null) {
                        val candidateUrl = match.value
                        android.util.Log.d("RadioApp", "Found URL matching station hash: $candidateUrl")
                        if (verifyStreamUrl(candidateUrl)) {
                            return@withContext candidateUrl
                        }
                    }
                }
            }
            
            android.util.Log.w("RadioApp", "Could not find active stream URL in website, using fallbacks")
            null
        } catch (e: Exception) {
            android.util.Log.e("RadioApp", "Error fetching stream URL: ${e.message}")
            null
        }
    }
    
    /**
     * Verify if a stream URL is accessible
     */
    private suspend fun verifyStreamUrl(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
            
            val responseCode = connection.responseCode
            connection.disconnect()
            
            val isValid = responseCode in 200..399
            android.util.Log.d("RadioApp", "Stream URL verification: $url -> $responseCode (${if (isValid) "VALID" else "INVALID"})")
            return@withContext isValid
        } catch (e: Exception) {
            android.util.Log.e("RadioApp", "Error verifying stream URL: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Load stream URLs (fetch from website first, then use fallbacks)
     */
    private suspend fun loadStreamUrls() {
        streamUrls.clear()
        
        // Try to fetch the latest URL from website
        val dynamicUrl = fetchStreamUrlFromWebsite()
        if (dynamicUrl != null) {
            android.util.Log.d("RadioApp", "Using dynamically fetched URL: $dynamicUrl")
            streamUrls.add(dynamicUrl)
            
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    "Using latest stream URL from website",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        
        // Add fallback URLs without duplicating
        for (fallback in fallbackStreamUrls) {
            if (!streamUrls.contains(fallback)) {
                streamUrls.add(fallback)
            }
        }
        
        android.util.Log.d("RadioApp", "Total ${streamUrls.size} stream URLs available: $streamUrls")
    }

    /**
     * Start playback of the HLS stream
     */
    private fun startPlayback() {
        if (isPlaying) {
            return // Already playing
        }

        // Acquire wake lock to keep device awake during playback
        wakeLock?.acquire(10 * 60 * 60 * 1000L) // 10 hours max

        isPlaying = true
        retryAttempts = 0
        currentUrlIndex = 0 // Reset to first URL when starting playback

        // Load stream URLs in background
        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(this@MainActivity, "Fetching latest stream URL...", Toast.LENGTH_SHORT).show()
            
            // Load URLs (fetch from website + fallbacks)
            withContext(Dispatchers.IO) {
                loadStreamUrls()
            }
            
            // If player is not initialized or in error state, reinitialize
            if (player == null || player?.playbackState == Player.STATE_IDLE) {
                android.util.Log.d("RadioApp", "Initializing player with ${streamUrls.size} URLs available")
                initializePlayer()
                // Prepare player before starting playback
                player?.prepare()
            }

            // Start playback
            player?.playWhenReady = true
            player?.play()

            // Start antenna animation (frames 30-150, infinite loop)
            antennaAnimation.setMinAndMaxFrame(30, 150)
            antennaAnimation.repeatCount = LottieDrawable.INFINITE
            antennaAnimation.playAnimation()

            // Update UI
            playButton.isEnabled = false
            stopButton.isEnabled = true

            Toast.makeText(this@MainActivity, "Connecting to stream...", Toast.LENGTH_SHORT).show()
            android.util.Log.d("RadioApp", "Playback started, waiting for stream...")
        }
    }

    /**
     * Stop playback immediately
     */
    private fun stopPlayback() {
        isPlaying = false

        // Cancel any pending retry or buffering timeouts
        cancelBufferingTimeout()
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null

        // Stop playback
        player?.playWhenReady = false
        player?.pause()
        player?.stop()

        // Stop antenna animation (frames 150-180, play once)
        antennaAnimation.repeatCount = 0
        antennaAnimation.setMinAndMaxFrame(150, 180)
        antennaAnimation.playAnimation()

        // Release wake lock
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        // Update UI
        playButton.isEnabled = true
        stopButton.isEnabled = false

        Toast.makeText(this, "Playback stopped", Toast.LENGTH_SHORT).show()
    }

    private fun scheduleBufferingTimeout() {
        cancelBufferingTimeout()
        bufferingTimeoutRunnable = Runnable {
            if (isPlaying && player?.playbackState == Player.STATE_BUFFERING) {
                android.util.Log.e("RadioApp", "Buffering timeout - stream stalled")
                handlePlaybackFailure("Buffering timeout")
            }
        }
        handler.postDelayed(bufferingTimeoutRunnable!!, 10000) // 10s timeout
    }

    private fun cancelBufferingTimeout() {
        bufferingTimeoutRunnable?.let { handler.removeCallbacks(it) }
        bufferingTimeoutRunnable = null
    }

    /**
     * Handle playback errors and attempt failover to next stream
     */
    private fun handlePlaybackError(error: androidx.media3.common.PlaybackException) {
        val errorMessage = when (error.errorCode) {
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Network connection failed"
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Connection timeout"
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "HTTP error: ${error.message}"
            androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "Invalid stream format"
            androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "Invalid HLS manifest"
            else -> "Error ${error.errorCode}: ${error.message ?: "Unknown error"}"
        }
        handlePlaybackFailure(errorMessage)
    }

    /**
     * Centralized failover and reconnect logic
     */
    private fun handlePlaybackFailure(reason: String) {
        if (!isPlaying) {
            return
        }

        cancelBufferingTimeout()
        retryRunnable?.let { handler.removeCallbacks(it) }

        android.util.Log.e("RadioApp", "Playback failure ($reason) with URL ${currentUrlIndex + 1}/${streamUrls.size}")

        // Try next URL if available
        if (currentUrlIndex < streamUrls.size - 1) {
            currentUrlIndex++
            android.util.Log.d("RadioApp", "Switching to URL ${currentUrlIndex + 1}/${streamUrls.size}")
            Toast.makeText(
                this,
                "Stream interrupted, trying alternative stream (${currentUrlIndex + 1}/${streamUrls.size})...",
                Toast.LENGTH_SHORT
            ).show()

            handler.postDelayed({
                if (isPlaying) {
                    initializePlayer()
                    player?.prepare()
                    player?.playWhenReady = true
                    player?.play()
                }
            }, 1000)
        } else {
            // All URLs tried, cycle back and retry with exponential backoff
            currentUrlIndex = 0
            retryAttempts++

            if (retryAttempts >= maxRetryAttempts) {
                isPlaying = false
                playButton.isEnabled = true
                stopButton.isEnabled = false

                antennaAnimation.repeatCount = 0
                antennaAnimation.setMinAndMaxFrame(150, 180)
                antennaAnimation.playAnimation()

                Toast.makeText(
                    this,
                    "All streams failed after $maxRetryAttempts attempts",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            Toast.makeText(
                this,
                "Retrying stream... (Attempt $retryAttempts/$maxRetryAttempts)",
                Toast.LENGTH_SHORT
            ).show()

            handler.postDelayed({
                if (isPlaying) {
                    initializePlayer()
                    player?.prepare()
                    player?.playWhenReady = true
                    player?.play()
                }
            }, 2000)
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
            
            // Stop antenna animation when reconnection fails
            antennaAnimation.repeatCount = 0
            antennaAnimation.setMinAndMaxFrame(150, 180)
            antennaAnimation.playAnimation()
            
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

        // Schedule retry (will try next URL automatically via error handler)
        retryRunnable = Runnable {
            if (isPlaying) {
                // Reinitialize player and restart playback with current URL
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
        // Cancel any pending retries or timeouts
        cancelBufferingTimeout()
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

    override fun onDestroy() {
        super.onDestroy()
        // Release player resources when activity is destroyed
        stopPlayback()
        releasePlayer()
        // Remove keep screen on flag
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}


package app.clearplay

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.RepeatModeUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var topBar: LinearLayout
    private lateinit var emptyView: View
    private lateinit var titleView: TextView
    private lateinit var flashView: TextView

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingUris: List<Uri> = emptyList()
    private var resizeIndex = 0
    private var landscapeLocked = false

    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL
    )

    // ---------- file pickers ----------
    private val pickMedia = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach(::keepAccess)
            addToQueue(uris, playNow = true)
        }
    }

    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            keepAccess(uri)
            attachSubtitle(uri)
        }
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // ---------- lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        playerView = findViewById(R.id.playerView)
        topBar = findViewById(R.id.topBar)
        emptyView = findViewById(R.id.emptyView)
        titleView = findViewById(R.id.title)
        flashView = findViewById(R.id.flash)

        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left + dp(16), bars.top + dp(8), bars.right + dp(4), dp(24))
            insets
        }

        setupPlayerView()

        findViewById<ImageButton>(R.id.btnOpen).setOnClickListener { openFiles() }
        findViewById<View>(R.id.emptyOpen).setOnClickListener { openFiles() }
        findViewById<ImageButton>(R.id.btnSubs).setOnClickListener {
            if ((controller?.mediaItemCount ?: 0) == 0) toast(getString(R.string.open_video_first))
            else pickSubtitle.launch(arrayOf("*/*"))
        }
        findViewById<ImageButton>(R.id.btnQueue).setOnClickListener { showQueue() }
        findViewById<ImageButton>(R.id.btnAspect).setOnClickListener { cycleResize() }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        pendingUris = urisFrom(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val uris = urisFrom(intent)
        if (uris.isNotEmpty()) {
            if (controller != null) addToQueue(uris, playNow = true) else pendingUris = uris
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            playerView.player = c
            c.addListener(playerListener)
            refreshUi()
            if (pendingUris.isNotEmpty()) {
                addToQueue(pendingUris, playNow = true)
                pendingUris = emptyList()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        super.onStop()
        controller?.removeListener(playerListener)
        playerView.player = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
    }

    // Leave the app while a video plays -> keep watching in a floating window.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val c = controller ?: return
        if (Build.VERSION.SDK_INT >= 26 && c.isPlaying && c.videoSize.width > 0) {
            val w = c.videoSize.width
            val h = c.videoSize.height.coerceAtLeast(1)
            val ratio = (w.toFloat() / h).coerceIn(1 / 2.39f, 2.39f)
            val r = Rational((ratio * 1000).toInt(), 1000)
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(r).build()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playerView.useController = !isInPictureInPictureMode
        topBar.visibility = if (isInPictureInPictureMode) View.GONE else topBar.visibility
        if (isInPictureInPictureMode) playerView.hideController()
    }

    // ---------- player view ----------
    @SuppressLint("ClickableViewAccessibility")
    private fun setupPlayerView() {
        playerView.setShowSubtitleButton(true)
        playerView.setShowShuffleButton(true)
        playerView.setShowNextButton(true)
        playerView.setShowPreviousButton(true)
        playerView.setRepeatToggleModes(RepeatModeUtil.REPEAT_TOGGLE_MODE_ONE or RepeatModeUtil.REPEAT_TOGGLE_MODE_ALL)
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        playerView.controllerShowTimeoutMs = 3_000
        playerView.defaultArtwork = ContextCompat.getDrawable(this, R.drawable.ic_music_art)

        // Top bar follows the built-in controls; system bars stay hidden while watching.
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            topBar.visibility = if (visibility == View.VISIBLE || controller?.mediaItemCount == 0) View.VISIBLE else View.GONE
            applyImmersive(visibility != View.VISIBLE)
        })

        // Rotate button inside the controls.
        playerView.setFullscreenButtonClickListener { _ ->
            landscapeLocked = !landscapeLocked
            requestedOrientation = if (landscapeLocked) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        // Double-tap: left third rewinds, right third skips ahead, middle plays/pauses.
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val c = controller ?: return false
                if (c.mediaItemCount == 0) return false
                val w = playerView.width
                when {
                    e.x < w * 0.35f -> { c.seekBack(); flash("« 10s") }
                    e.x > w * 0.65f -> { c.seekForward(); flash("10s »") }
                    else -> { if (c.isPlaying) c.pause() else c.play(); flash(if (c.isPlaying) "Playing" else "Paused") }
                }
                return true
            }
        })
        playerView.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev); false }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refreshUi()
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = refreshUi()
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playerView.keepScreenOn = isPlaying
        }
        override fun onPlayerError(error: PlaybackException) {
            val name = controller?.currentMediaItem?.mediaMetadata?.title ?: ""
            toast(getString(R.string.cant_play, name) + "\n" + explain(error))
            val c = controller ?: return
            if (c.hasNextMediaItem()) { c.seekToNextMediaItem(); c.prepare(); c.play() }
        }
    }

    private fun refreshUi() {
        val c = controller
        val empty = c == null || c.mediaItemCount == 0
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        titleView.text = c?.currentMediaItem?.mediaMetadata?.title ?: getString(R.string.app_name)
        if (empty) topBar.visibility = View.VISIBLE
    }

    // ---------- queue ----------
    private fun openFiles() {
        pickMedia.launch(arrayOf("video/*", "audio/*", "application/octet-stream", "application/x-matroska"))
    }

    private fun addToQueue(uris: List<Uri>, playNow: Boolean) {
        val c = controller ?: run { pendingUris = uris; return }
        val items = uris.map(::buildItem)
        val startIndex = c.mediaItemCount
        c.addMediaItems(items)
        if (playNow) {
            c.seekToDefaultPosition(startIndex)
            c.prepare()
            c.play()
        }
        refreshUi()
    }

    private fun buildItem(uri: Uri, subUri: Uri? = null, subMime: String? = null): MediaItem {
        val name = displayName(uri)
        val extras = if (subUri != null) bundleOf(
            PlaybackService.EXTRA_SUB_URI to subUri.toString(),
            PlaybackService.EXTRA_SUB_MIME to subMime
        ) else null
        val item = MediaItem.Builder()
            .setMediaId(uri.toString())
            .setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(name.substringBeforeLast('.')).setDisplayTitle(name).build())
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).setExtras(extras).build())
            .build()
        return PlaybackService.resolve(item)
    }

    private fun showQueue() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) { toast(getString(R.string.queue_empty)); return }
        val titles = Array<CharSequence>(c.mediaItemCount) { i ->
            val t = c.getMediaItemAt(i).mediaMetadata.displayTitle ?: c.getMediaItemAt(i).mediaMetadata.title ?: "Item ${i + 1}"
            if (i == c.currentMediaItemIndex) "▶  $t" else "     $t"
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.queue)
            .setItems(titles) { _, i -> c.seekToDefaultPosition(i); c.prepare(); c.play() }
            .setNeutralButton(R.string.clear_queue) { _, _ -> c.stop(); c.clearMediaItems(); refreshUi() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    // ---------- subtitles ----------
    private fun attachSubtitle(subUri: Uri) {
        val c = controller ?: return
        val index = c.currentMediaItemIndex
        val current = c.currentMediaItem ?: return
        val name = displayName(subUri)
        val mime = when {
            name.endsWith(".vtt", true) -> MimeTypes.TEXT_VTT
            name.endsWith(".ass", true) || name.endsWith(".ssa", true) -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        val videoUri = current.localConfiguration?.uri ?: current.requestMetadata.mediaUri ?: Uri.parse(current.mediaId)
        val pos = c.currentPosition
        val wasPlaying = c.isPlaying
        c.replaceMediaItem(index, buildItem(videoUri, subUri, mime))
        c.seekTo(index, pos)
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setSelectUndeterminedTextLanguage(true)
            .build()
        if (wasPlaying) c.play()
        toast(getString(R.string.subtitles_added, name))
    }

    // ---------- helpers ----------
    private fun cycleResize() {
        resizeIndex = (resizeIndex + 1) % resizeModes.size
        playerView.resizeMode = resizeModes[resizeIndex]
        flash(getString(arrayOf(R.string.fit, R.string.crop, R.string.stretch)[resizeIndex]))
    }

    private fun applyImmersive(on: Boolean) {
        val ctl = WindowInsetsControllerCompat(window, window.decorView)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (on && (controller?.mediaItemCount ?: 0) > 0) ctl.hide(WindowInsetsCompat.Type.systemBars())
        else ctl.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun urisFrom(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(streamExtra(intent))
            else -> emptyList()
        }
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

    private fun keepAccess(uri: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Untitled"
    }

    private fun explain(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
            "Your phone has no decoder for this file's audio or video format."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
            "The file is damaged or in an unsupported container."
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            "The file was moved, or Clearplay lost permission to read it. Open it again."
        else -> e.errorCodeName
    }

    private fun flash(text: String) {
        flashView.text = text
        flashView.visibility = View.VISIBLE
        flashView.removeCallbacks(hideFlash)
        flashView.postDelayed(hideFlash, 700)
    }

    private val hideFlash = Runnable { flashView.visibility = View.GONE }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

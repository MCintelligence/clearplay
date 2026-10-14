package app.clearplay

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Owns the player so audio keeps going with the screen off, and gives you
 * lock-screen, notification and headset controls for free via MediaSession.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var positions: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var currentId: String? = null

    private val periodicSave = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        positions = getSharedPreferences("resume_positions", MODE_PRIVATE)

        // Prefer extension decoders if you add one later (e.g. FFmpeg), otherwise use the phone's.
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // The previous file finished on its own: forget its resume point.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                ) {
                    currentId?.let { positions.edit().remove(it).apply() }
                }
                currentId = mediaItem?.mediaId
                val saved = currentId?.let { positions.getLong(it, 0L) } ?: 0L
                if (saved > 5_000 && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    player.seekTo(saved)
                    Toast.makeText(
                        applicationContext,
                        getString(R.string.resumed_at, formatTime(saved)),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(periodicSave)
                if (isPlaying) handler.postDelayed(periodicSave, 5_000) else savePosition()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    currentId?.let { positions.edit().remove(it).apply() }
                }
            }
        })

        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .build()
    }

    private fun savePosition() {
        val player = session?.player ?: return
        val id = currentId ?: return
        val pos = player.currentPosition
        val dur = player.duration
        val nearEnd = dur != C.TIME_UNSET && pos > dur - 5_000
        positions.edit().apply {
            if (pos < 5_000 || nearEnd) remove(id) else putLong(id, pos)
        }.apply()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(periodicSave)
        savePosition()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /**
     * Items sent from the screen may arrive without their file URI attached, so rebuild
     * them here from the request metadata (URI + optional side-loaded subtitle).
     */
    private inner class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { resolve(it) }.toMutableList())
    }

    companion object {
        const val EXTRA_SUB_URI = "clearplay.sub_uri"
        const val EXTRA_SUB_MIME = "clearplay.sub_mime"

        fun resolve(item: MediaItem): MediaItem {
            val uri = item.localConfiguration?.uri
                ?: item.requestMetadata.mediaUri
                ?: Uri.parse(item.mediaId)
            val builder = item.buildUpon().setUri(uri)
            val extras = item.requestMetadata.extras
            val sub = extras?.getString(EXTRA_SUB_URI)
            if (sub != null) {
                builder.setSubtitleConfigurations(
                    listOf(
                        MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub))
                            .setMimeType(extras.getString(EXTRA_SUB_MIME))
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .setLabel("Subtitles")
                            .build()
                    )
                )
            }
            return builder.build()
        }

        fun formatTime(ms: Long): String {
            val s = ms / 1000
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
        }
    }
}

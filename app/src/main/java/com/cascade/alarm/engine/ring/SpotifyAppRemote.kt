package com.cascade.alarm.engine.ring

import android.content.Context
import android.media.AudioManager
import androidx.core.os.UserManagerCompat
import com.cascade.alarm.BuildConfig
import com.cascade.alarm.engine.EngineLog
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.OfflineModeException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.PlayerContext
import com.spotify.protocol.types.PlayerState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * The Spotify app through its App Remote SDK: Cascade sends it a URI to play over Android IPC
 * and Spotify does the rest. No tokens pass through Cascade, and Cascade itself goes online for
 * none of it. Every SDK call stays in this file.
 */
class AppRemoteSpotify(private val context: Context) : SpotifyRemote {
    override suspend fun connect(authorize: Boolean): SpotifyRemote.Connection {
        if (!isAvailable()) return SpotifyRemote.Connection.Failed("spotify_unavailable")
        // Spotify cannot run before the first unlock after a restart; don't wait for it.
        if (!UserManagerCompat.isUserUnlocked(context)) return SpotifyRemote.Connection.Failed("spotify_locked")
        if (!SpotifyAppRemote.isSpotifyInstalled(context)) return SpotifyRemote.Connection.Failed("spotify_not_installed")
        val params = ConnectionParams.Builder(BuildConfig.SPOTIFY_CLIENT_ID)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(authorize)
            .build()
        return suspendCancellableCoroutine { cont ->
            SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
                override fun onConnected(remote: SpotifyAppRemote) {
                    // A connection that arrives after a timeout is nobody's; close it.
                    if (cont.isActive) cont.resume(SpotifyRemote.Connection.Connected(Session(remote)))
                    else SpotifyAppRemote.disconnect(remote)
                }

                override fun onFailure(error: Throwable) {
                    EngineLog.w("spotify connect failed: ${error.javaClass.simpleName}: ${error.message}")
                    if (cont.isActive) cont.resume(SpotifyRemote.Connection.Failed(reasonFor(error)))
                }
            })
        }
    }

    private class Session(private val remote: SpotifyAppRemote) : SpotifySession {
        private var states: Subscription<PlayerState>? = null
        private var contexts: Subscription<PlayerContext>? = null
        private var playing = false
        private var stoppedListener: (() -> Unit)? = null
        private var closed = false

        override var title: String? = null
            private set

        override suspend fun play(uri: String): Boolean {
            val started = CompletableDeferred<Boolean>()
            contexts = remote.playerApi.subscribeToPlayerContext().setEventCallback { context ->
                if (context.uri == uri && !context.title.isNullOrBlank()) title = context.title
            }
            states = remote.playerApi.subscribeToPlayerState().setEventCallback { state ->
                val nowPlaying = !state.isPaused && state.track != null
                if (nowPlaying) started.complete(true)
                if (playing && !nowPlaying) stopped()
                playing = nowPlaying
            }
            states?.setErrorCallback { stopped() }
            remote.playerApi.play(uri).setErrorCallback { error ->
                EngineLog.w("spotify play failed: ${error.message}")
                started.complete(false)
            }
            return started.await()
        }

        override fun onStopped(listener: () -> Unit) {
            stoppedListener = listener
        }

        private fun stopped() {
            if (closed) return
            stoppedListener?.invoke()
            stoppedListener = null
        }

        override fun close() {
            if (closed) return
            closed = true
            stoppedListener = null
            states?.cancel()
            contexts?.cancel()
            runCatching { if (remote.isConnected) remote.playerApi.pause() }
            SpotifyAppRemote.disconnect(remote)
        }
    }

    companion object {
        /** Must match the redirect URI registered on Spotify's dashboard exactly. */
        const val REDIRECT_URI = "cascade://spotify-callback"

        /** Built with Spotify and a Client ID; release builds normally leave it out (SPEC §9). */
        fun isAvailable(): Boolean = BuildConfig.SPOTIFY_ENABLED && BuildConfig.SPOTIFY_CLIENT_ID.isNotBlank()

        fun isInstalled(context: Context): Boolean = runCatching { SpotifyAppRemote.isSpotifyInstalled(context) }.getOrDefault(false)

        private fun reasonFor(error: Throwable): String = when (error) {
            is CouldNotFindSpotifyApp -> "spotify_not_installed"
            is NotLoggedInException -> "spotify_signed_out"
            is UserNotAuthorizedException -> "spotify_not_authorized"
            is OfflineModeException -> "spotify_offline"
            else -> "spotify_unavailable"
        }
    }
}

/**
 * The media stream's volume, moved with the alarm's ramp. Full volume matches the alarm stream's
 * share of its range, so Spotify is about as loud as the tone it replaces.
 */
class AndroidMusicStream(context: Context) : MusicStream {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var saved: Int? = null
    private var alarmShare = 1f
    /** The ramp calls [set] five times a second; the stream only has about 15 steps. */
    private var lastIndex = -1

    override fun take() {
        saved = runCatching { audio.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
        alarmShare = runCatching {
            audio.getStreamVolume(AudioManager.STREAM_ALARM).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        }.getOrDefault(1f).coerceIn(0.1f, 1f)
    }

    override fun set(gain: Float) {
        runCatching {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val index = (max * alarmShare * gain).roundToInt().coerceIn(1, max)
            if (index != lastIndex) audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
            lastIndex = index
        }.onFailure { EngineLog.e("cannot set media volume for Spotify", it) }
    }

    override fun restore() {
        saved?.let { volume -> runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0) } }
        saved = null
        lastIndex = -1
    }
}

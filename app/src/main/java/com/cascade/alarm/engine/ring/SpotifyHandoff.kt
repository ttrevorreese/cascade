package com.cascade.alarm.engine.ring

import com.cascade.alarm.engine.EngineLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The Spotify app, as far as an alarm needs it. The real one is [AppRemoteSpotify]. */
interface SpotifyRemote {
    sealed interface Connection {
        data class Connected(val session: SpotifySession) : Connection

        /** [reason] is a `spotify_…` code for the event log ([SpotifyHandoff.REASONS]). */
        data class Failed(val reason: String) : Connection
    }

    /** [authorize] lets Spotify show its approval screen; never at alarm time. Main thread. */
    suspend fun connect(authorize: Boolean): Connection
}

interface SpotifySession {
    /** Starts [uri] and returns true once Spotify reports it playing. */
    suspend fun play(uri: String): Boolean

    /** The playlist or album name, once Spotify has said it. */
    val title: String?

    /** Called once if Spotify pauses or the connection drops; not after [close]. */
    fun onStopped(listener: () -> Unit)

    /** Pauses playback and disconnects. */
    fun close()
}

/** Spotify plays on the media stream, so its loudness is that stream's volume. */
interface MusicStream {
    /** Remembers the current volume, to give back in [restore]. */
    fun take()

    /** [gain] is the alarm's volume now, 0–1 of its full volume. */
    fun set(gain: Float)
    fun restore()
}

/**
 * Hands a ringing alarm over to Spotify (SPEC §9). The local sound is already ringing; it fades
 * out only once Spotify is actually playing, and comes straight back if Spotify stops. Every
 * failure leaves the local sound ringing and is reported, so Home can say what happened.
 */
class SpotifyHandoff(
    private val scope: CoroutineScope,
    private val remote: SpotifyRemote,
    private val music: MusicStream,
    /** How much of the local sound to keep: 1 while Spotify is not playing, 0 once it is. */
    private val setLocalShare: (Float) -> Unit,
    private val onFallback: (reason: String) -> Unit,
) {
    private var job: Job? = null
    private var session: SpotifySession? = null
    private var tookMusic = false
    private var gain = 0f
    private var stopped = false

    fun start(uri: String, initialGain: Float) {
        gain = initialGain
        job = scope.launch {
            val connection = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) { remote.connect(authorize = false) }
                ?: SpotifyRemote.Connection.Failed("spotify_timeout")
            if (connection is SpotifyRemote.Connection.Failed) return@launch fail(connection.reason)
            val connected = (connection as SpotifyRemote.Connection.Connected).session
            session = connected
            music.take()
            tookMusic = true
            music.set(gain)
            val playing = withTimeoutOrNull(START_TIMEOUT_MILLIS) { connected.play(uri) } == true
            if (!playing) return@launch fail("spotify_did_not_play")
            EngineLog.i("spotify playing $uri")
            connected.onStopped {
                if (stopped) return@onStopped
                EngineLog.w("spotify stopped during the ring; local sound back")
                job?.cancel()
                setLocalShare(1f)
                onFallback("spotify_stopped")
            }
            for (step in 1..FADE_STEPS) {
                setLocalShare(1f - step.toFloat() / FADE_STEPS)
                delay(FADE_MILLIS / FADE_STEPS)
            }
        }
    }

    /** The ramp, ducking and read-aloud all move Spotify's volume as they move the tone's. */
    fun setGain(value: Float) {
        gain = value
        if (tookMusic) music.set(value)
    }

    fun stop() {
        stopped = true
        job?.cancel()
        job = null
        session?.close()
        session = null
        if (tookMusic) music.restore()
        tookMusic = false
    }

    private fun fail(reason: String) {
        EngineLog.w("spotify did not take over ($reason); the backup keeps ringing")
        session?.close()
        session = null
        if (tookMusic) music.restore()
        tookMusic = false
        setLocalShare(1f)
        onFallback(reason)
    }

    companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000L
        const val START_TIMEOUT_MILLIS = 10_000L
        const val FADE_MILLIS = 2_000L
        private const val FADE_STEPS = 10

        /** Every reason a hand-off can fail with, for Home's wording. */
        val REASONS = setOf(
            "spotify_locked", "spotify_not_installed", "spotify_signed_out", "spotify_not_authorized",
            "spotify_offline", "spotify_unavailable", "spotify_timeout", "spotify_did_not_play", "spotify_stopped",
        )
    }
}

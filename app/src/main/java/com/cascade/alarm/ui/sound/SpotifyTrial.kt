package com.cascade.alarm.ui.sound

import android.content.Context
import com.cascade.alarm.engine.ring.AppRemoteSpotify
import com.cascade.alarm.engine.ring.SpotifyRemote
import com.cascade.alarm.engine.ring.SpotifySession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Try it in Spotify" on the Sound page: the same connection an alarm makes, except that Spotify
 * may show its approval screen. Approving here once is what lets alarms connect later without it.
 */
@Singleton
class SpotifyTrial @Inject constructor(@ApplicationContext private val context: Context) {
    sealed interface Status {
        data object Idle : Status
        data object Connecting : Status

        /** [title] is the playlist's name, once Spotify has said it. */
        data class Playing(val title: String?) : Status
        data class Failed(val reason: String) : Status
    }

    private val remote: SpotifyRemote by lazy { AppRemoteSpotify(context) }
    private var job: Job? = null
    private var session: SpotifySession? = null
    private val mutableStatus = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = mutableStatus.asStateFlow()

    val isAvailable: Boolean get() = AppRemoteSpotify.isAvailable()

    fun isInstalled(): Boolean = AppRemoteSpotify.isInstalled(context)

    /** Main thread. */
    fun play(uri: String, scope: CoroutineScope) {
        stop()
        mutableStatus.value = Status.Connecting
        job = scope.launch {
            // Longer than an alarm waits: the user may be reading Spotify's approval screen.
            val connection = withTimeoutOrNull(APPROVAL_MILLIS) { remote.connect(authorize = true) }
                ?: SpotifyRemote.Connection.Failed("spotify_timeout")
            if (connection is SpotifyRemote.Connection.Failed) {
                mutableStatus.value = Status.Failed(connection.reason)
                return@launch
            }
            val connected = (connection as SpotifyRemote.Connection.Connected).session
            session = connected
            val playing = withTimeoutOrNull(START_MILLIS) { connected.play(uri) } == true
            if (!playing) {
                stop()
                mutableStatus.value = Status.Failed("spotify_did_not_play")
                return@launch
            }
            mutableStatus.value = Status.Playing(connected.title)
            connected.onStopped { stop() }
        }
    }

    /** The name Spotify gave the playlist during the last trial, if it did. */
    fun title(): String? = session?.title ?: (status.value as? Status.Playing)?.title

    fun stop() {
        job?.cancel()
        job = null
        session?.close()
        session = null
        mutableStatus.value = Status.Idle
    }

    private companion object {
        const val APPROVAL_MILLIS = 60_000L
        const val START_MILLIS = 10_000L
    }
}

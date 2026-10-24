package com.cascade.alarm.engine.ring

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpotifyHandoffTest {
    private class FakeSession(private val plays: Boolean?) : SpotifySession {
        var played: String? = null
        var closed = false
        private var listener: (() -> Unit)? = null
        override val title: String? = "Wake up"

        /** Null [plays] never answers. */
        override suspend fun play(uri: String): Boolean {
            played = uri
            return plays ?: awaitCancellation()
        }

        override fun onStopped(listener: () -> Unit) {
            this.listener = listener
        }

        fun stopFromSpotify() = listener?.invoke()

        override fun close() {
            closed = true
        }
    }

    private class FakeRemote(private val connection: SpotifyRemote.Connection?) : SpotifyRemote {
        var authorized: Boolean? = null

        override suspend fun connect(authorize: Boolean): SpotifyRemote.Connection {
            authorized = authorize
            return connection ?: awaitCancellation()
        }
    }

    private class FakeMusic : MusicStream {
        var taken = false
        var restored = false
        val gains = mutableListOf<Float>()

        override fun take() {
            taken = true
        }

        override fun set(gain: Float) {
            gains += gain
        }

        override fun restore() {
            restored = true
        }
    }

    private val shares = mutableListOf<Float>()
    private val fallbacks = mutableListOf<String>()
    private val music = FakeMusic()

    private fun TestScope.handoff(remote: SpotifyRemote) =
        SpotifyHandoff(this, remote, music, { shares += it }, { fallbacks += it })

    private fun connected(session: FakeSession) = FakeRemote(SpotifyRemote.Connection.Connected(session))

    @Test
    fun `once Spotify plays, the tone fades out and Spotify takes the alarm's volume`() = runTest {
        val session = FakeSession(plays = true)
        val remote = connected(session)
        val handoff = handoff(remote)
        handoff.start("spotify:playlist:x", initialGain = 0.2f)
        advanceUntilIdle()
        assertEquals(false, remote.authorized, "an alarm never shows Spotify's approval screen")
        assertEquals("spotify:playlist:x", session.played)
        assertEquals(0f, shares.last())
        assertTrue(shares.zipWithNext().all { (a, b) -> b < a }, "the tone fades rather than cutting off")
        assertTrue(music.taken)
        assertEquals(0.2f, music.gains.first())
        handoff.setGain(0.6f)
        assertEquals(0.6f, music.gains.last())
        assertTrue(fallbacks.isEmpty())
    }

    @Test
    fun `when Spotify cannot be reached the tone keeps ringing and Home is told why`() = runTest {
        handoff(FakeRemote(SpotifyRemote.Connection.Failed("spotify_signed_out"))).start("spotify:playlist:x", 0.2f)
        advanceUntilIdle()
        assertEquals(listOf("spotify_signed_out"), fallbacks)
        assertTrue(shares.all { it == 1f })
        assertFalse(music.taken, "the media volume is left alone")
    }

    @Test
    fun `a Spotify that never answers is given up on`() = runTest {
        handoff(FakeRemote(null)).start("spotify:playlist:x", 0.2f)
        advanceTimeBy(SpotifyHandoff.CONNECT_TIMEOUT_MILLIS + 1)
        assertEquals(listOf("spotify_timeout"), fallbacks)
    }

    @Test
    fun `connected but not playing gives the media volume back`() = runTest {
        val session = FakeSession(plays = null)
        handoff(connected(session)).start("spotify:playlist:x", 0.2f)
        advanceTimeBy(SpotifyHandoff.START_TIMEOUT_MILLIS + 1)
        assertEquals(listOf("spotify_did_not_play"), fallbacks)
        assertTrue(session.closed)
        assertTrue(music.restored)
        assertEquals(1f, shares.last())
    }

    @Test
    fun `if Spotify stops mid-alarm the tone comes straight back`() = runTest {
        val session = FakeSession(plays = true)
        handoff(connected(session)).start("spotify:playlist:x", 0.5f)
        advanceUntilIdle()
        session.stopFromSpotify()
        assertEquals(1f, shares.last())
        assertEquals(listOf("spotify_stopped"), fallbacks)
    }

    @Test
    fun `dismissing pauses Spotify and gives the media volume back`() = runTest {
        val session = FakeSession(plays = true)
        val handoff = handoff(connected(session))
        handoff.start("spotify:playlist:x", 0.5f)
        advanceUntilIdle()
        handoff.stop()
        session.stopFromSpotify()
        assertTrue(session.closed)
        assertTrue(music.restored)
        assertTrue(fallbacks.isEmpty(), "pausing it ourselves is not a failure")
    }
}

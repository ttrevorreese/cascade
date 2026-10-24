package com.cascade.alarm.data.sound

import com.cascade.alarm.data.sound.SpotifyLinks.Parsed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpotifyLinksTest {
    private val id = "37i9dQZF1DXcBWIGoYBM5M"

    @Test
    fun `a copied playlist link plays that playlist`() {
        assertEquals(Parsed.Playable("spotify:playlist:$id", "playlist"), SpotifyLinks.parse("https://open.spotify.com/playlist/$id?si=1a2b3c4d5e"))
    }

    @Test
    fun `albums, regional links, old user links and URIs all work`() {
        assertEquals(Parsed.Playable("spotify:album:$id", "album"), SpotifyLinks.parse("https://open.spotify.com/album/$id"))
        assertEquals(Parsed.Playable("spotify:playlist:$id", "playlist"), SpotifyLinks.parse("https://open.spotify.com/intl-de/playlist/$id"))
        assertEquals(Parsed.Playable("spotify:playlist:$id", "playlist"), SpotifyLinks.parse("open.spotify.com/user/someone/playlist/$id"))
        assertEquals(Parsed.Playable("spotify:playlist:$id", "playlist"), SpotifyLinks.parse("spotify:playlist:$id"))
    }

    @Test
    fun `shared text around the link is ignored`() {
        assertEquals(
            Parsed.Playable("spotify:playlist:$id", "playlist"),
            SpotifyLinks.parse("  Wake up songs https://open.spotify.com/playlist/$id?si=x \n"),
        )
    }

    @Test
    fun `a single track or an artist is not something an alarm can play through`() {
        assertEquals(Parsed.NotSpotify, SpotifyLinks.parse("https://open.spotify.com/track/$id"))
        assertEquals(Parsed.NotSpotify, SpotifyLinks.parse("https://open.spotify.com/artist/$id"))
    }

    @Test
    fun `short links and other text are told apart`() {
        assertEquals(Parsed.ShortLink, SpotifyLinks.parse("https://spotify.link/AbCdEf123"))
        assertEquals(Parsed.NotSpotify, SpotifyLinks.parse("https://example.com/playlist/$id"))
        assertEquals(Parsed.NotSpotify, SpotifyLinks.parse(""))
    }

    @Test
    fun `a stored URI shows as a web link again`() =
        assertEquals("https://open.spotify.com/playlist/$id", SpotifyLinks.webLink("spotify:playlist:$id"))
}

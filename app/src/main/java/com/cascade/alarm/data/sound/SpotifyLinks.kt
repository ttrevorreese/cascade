package com.cascade.alarm.data.sound

/**
 * Turns what Spotify's Share → Copy link gives into the URI the Spotify app plays, without asking
 * Spotify's servers anything (Cascade makes no network calls, SPEC §11).
 */
object SpotifyLinks {
    sealed interface Parsed {
        data class Playable(val uri: String, val kind: String) : Parsed

        /** A spotify.link short link: it only says where to go once opened, which needs the network. */
        data object ShortLink : Parsed
        data object NotSpotify : Parsed
    }

    /** What an alarm can start: a whole list of tracks. */
    private val KINDS = setOf("playlist", "album")

    // open.spotify.com/playlist/ID, /intl-de/playlist/ID, /user/x/playlist/ID, with any ?si=…
    private val WEB = Regex("""(?:https?://)?open\.spotify\.com/(?:intl-[a-z-]+/)?(?:user/[^/]+/)?([a-z]+)/([A-Za-z0-9]{22})""")
    private val URI = Regex("""spotify:(?:user:[^:]+:)?([a-z]+):([A-Za-z0-9]{22})""")
    private val SHORT = Regex("""(?:https?://)?(?:spotify\.link|spoti\.fi)/\S+""")

    fun parse(text: String): Parsed {
        val trimmed = text.trim()
        val match = WEB.find(trimmed) ?: URI.find(trimmed)
        if (match != null) {
            val (kind, id) = match.destructured
            return if (kind in KINDS) Parsed.Playable("spotify:$kind:$id", kind) else Parsed.NotSpotify
        }
        return if (SHORT.containsMatchIn(trimmed)) Parsed.ShortLink else Parsed.NotSpotify
    }

    /** The web link for [uri], to show in the text field again. */
    fun webLink(uri: String): String {
        val parts = uri.split(":")
        return if (parts.size == 3) "https://open.spotify.com/${parts[1]}/${parts[2]}" else uri
    }
}

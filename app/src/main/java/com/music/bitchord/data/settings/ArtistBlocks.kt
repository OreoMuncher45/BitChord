package com.music.bitchord.data.settings

import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import kotlin.jvm.JvmName

/**
 * The one place hidden artists are applied to lists.
 *
 * Every surface that can put an act in front of the listener — search, Home,
 * FLOW, autoplay, recommended shelves — goes through these helpers rather than
 * re-deriving "is this the same artist" from a name. The identity rules live in
 * [AppSettings.isArtistBlocked] so hiding an act from a song row and hiding it
 * from its own page always agree.
 */
@JvmName("songsWithoutBlockedArtists")
fun List<Song>.withoutBlockedArtists(): List<Song> =
    if (AppSettings.blockedArtists.value.isEmpty() && !AppSettings.blockAiMusic.value) this
    else filterNot(AppSettings::isSongBlocked)

fun UiState<List<Song>>.withoutBlockedArtists(): UiState<List<Song>> = when (this) {
    is UiState.Success -> UiState.Success(data.withoutBlockedArtists())
    else -> this
}

/** A shelf with hidden acts removed; null when nothing is left to show. */
fun HomeShelf.withoutBlockedArtists(): HomeShelf? {
    if (AppSettings.blockedArtists.value.isEmpty() && !AppSettings.blockAiMusic.value) return this
    val kept = items.filterNot { it.isBlockedArtistItem() }
    return if (kept.isEmpty()) null else copy(items = kept)
}

@JvmName("shelvesWithoutBlockedArtists")
fun List<HomeShelf>.withoutBlockedArtists(): List<HomeShelf> =
    if (AppSettings.blockedArtists.value.isEmpty() && !AppSettings.blockAiMusic.value) this
    else mapNotNull { it.withoutBlockedArtists() }

/**
 * Whether a card is an artist the listener hid.
 *
 * Only channel-shaped browse ids are treated as artist ids: an album or a
 * playlist can share a string with nothing, but reading every browse id as an
 * artist would hide a release whose id happened to match. Song cards are
 * matched by the credit YouTube prints on the card.
 */
fun ShelfItem.isBlockedArtistItem(): Boolean {
    val strictAi = AppSettings.blockAiMusic.value
    if (AppSettings.blockedArtists.value.isEmpty() && !strictAi) return false
    val channelId = browseId?.takeIf { it.startsWith("UC") }
    val artist = subtitle.artistFromSubtitle()
    if (AppSettings.isArtistBlocked(channelId, artist)) return true
    if (strictAi && AppSettings.isArtistAi(channelId, artist)) return true
    // A song card whose linked track is by a hidden act, when the card carries
    // the channel id rather than a name.
    if (videoId != null) return false
    if (AppSettings.isArtistBlocked(channelId, title)) return true
    return strictAi && AppSettings.isArtistAi(channelId, title)
}

fun SearchResult.isBlockedArtistResult(): Boolean = when (this) {
    is SearchResult.TopTrack -> AppSettings.isSongBlocked(song)
    is SearchResult.Track -> AppSettings.isSongBlocked(song)
    is SearchResult.Browse -> item.isBlockedArtist()
}

fun BrowseItem.isBlockedArtist(): Boolean {
    if (type != BrowseType.ARTIST) return false
    if (AppSettings.isArtistBlocked(browseId, title)) return true
    return AppSettings.blockAiMusic.value && AppSettings.isArtistAi(browseId, title)
}

@JvmName("searchResultsWithoutBlockedArtists")
fun List<SearchResult>.withoutBlockedArtists(): List<SearchResult> =
    if (AppSettings.blockedArtists.value.isEmpty() && !AppSettings.blockAiMusic.value) this
    else filterNot(SearchResult::isBlockedArtistResult)

/**
 * Recommendations on a detail page. A blocked artist's own page is left alone:
 * it has to keep rendering so the listener can unhide it from the same menu
 * that hid it.
 */
fun DetailPage.withoutBlockedRecommendations(): DetailPage {
    if (AppSettings.blockedArtists.value.isEmpty() && !AppSettings.blockAiMusic.value) return this
    if (type == BrowseType.ARTIST &&
        (AppSettings.isArtistBlocked(browseId, title) ||
            (AppSettings.blockAiMusic.value && AppSettings.isArtistAi(browseId, title)))
    ) return this
    return copy(
        songs = songs.withoutBlockedArtists(),
        sections = sections.withoutBlockedArtists(),
    )
}

private fun String.artistFromSubtitle(): String =
    substringBefore(" • ").substringBefore("·").trim()

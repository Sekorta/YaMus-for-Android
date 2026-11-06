package ru.yamus.app.core.models

/**
 * Модели ответов API Яндекс Музыки. Все поля сделаны nullable/со значениями
 * по умолчанию: API в разных эндпоинтах отдаёт разные наборы полей.
 */

data class AccountAboutResponse(
    val result: AccountInfo? = null
)

data class AccountInfo(
    val uid: Long = 0,
    val login: String? = null,
    val publicName: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val avatarUri: String? = null,
    val avatarPortraitUri: String? = null,
    val avatarId: String? = null,
    val hasPlus: Boolean = false,
    val hasMusicSubscription: Boolean = false,
    val serviceAvailable: Boolean = true
)

data class Artist(
    val id: Long = 0,
    val name: String? = null,
    val various: Boolean = false,
    val composer: Boolean = false,
    val cover: ArtistCover? = null,
    val coverUri: String? = null,
    val counts: ArtistCounts? = null,
    val genres: List<String>? = null
)

data class ArtistCover(
    val uri: String? = null,
    val type: String? = null,
    val prefix: String? = null
)

data class ArtistCounts(
    val tracks: Int = 0,
    val directAlbums: Int = 0,
    val alsoAlbums: Int = 0,
    val alsoTracks: Int = 0
)

data class TrackPosition(
    val volume: Int = 1,
    val index: Int = 1
)

data class Album(
    val id: Long = 0,
    val title: String? = null,
    val coverUri: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val trackCount: Int? = null,
    val releaseDate: String? = null,
    val artists: List<Artist>? = null,
    val trackPosition: TrackPosition? = null,
    val volumes: List<List<Track>>? = null
)

data class LyricsInfo(
    val hasAvailableSyncLyrics: Boolean = false,
    val hasAvailableTextLyrics: Boolean = false
)

data class Track(
    val id: String = "",
    val realId: String? = null,
    val title: String? = null,
    val version: String? = null,
    val durationMs: Long = 0,
    val coverUri: String? = null,
    val available: Boolean = true,
    val artists: List<Artist>? = null,
    val albums: List<Album>? = null,
    val lyricsInfo: LyricsInfo? = null
)

data class TracksResponse(
    val result: List<Track>? = null
)

data class PlaylistsListResponse(
    val result: List<PlaylistSummary>? = null
)

data class PlaylistSummary(
    val kind: Long = 0,
    val title: String? = null,
    val trackCount: Int = 0,
    val revision: Int? = null,
    val snapshot: String? = null,
    val cover: PlaylistCover? = null,
    val owner: PlaylistOwner? = null,
    val uid: Long = 0,
    val description: String? = null
)

data class PlaylistCover(
    val uri: String? = null,
    val type: String? = null,
    val prefix: String? = null
)

data class PlaylistOwner(
    val uid: Long = 0,
    val login: String? = null,
    val name: String? = null
)

data class PlaylistResponse(
    val result: PlaylistDetail? = null
)

data class PlaylistDetail(
    val kind: Long = 0,
    val title: String? = null,
    val trackCount: Int = 0,
    val revision: Int? = null,
    val snapshot: String? = null,
    val cover: PlaylistCover? = null,
    val owner: PlaylistOwner? = null,
    val uid: Long = 0,
    val description: String? = null,
    val tracks: List<PlaylistTrackItem>? = null
)

data class PlaylistTrackItem(
    val id: String? = null,
    val track: Track? = null
)

data class LikeIdsResponse(
    val result: LikeIdsResult? = null
)

data class LikeIdsResult(
    val library: LikeLibrary? = null
)

data class LikeLibrary(
    val tracks: List<LikeIdItem>? = null
)

data class LikeIdItem(
    val id: String? = null,
    val albumId: Long? = null,
    val timestamp: String? = null
)

data class SearchResponse(
    val result: SearchResult? = null
)

data class SearchResult(
    val tracks: SearchSection<Track>? = null,
    val albums: SearchSection<Album>? = null,
    val artists: SearchSection<Artist>? = null,
    val playlists: SearchSection<PlaylistSummary>? = null
)

data class SearchSection<T>(
    val total: Int = 0,
    val perPage: Int = 0,
    val results: List<T>? = null
)

data class AlbumWithTracksResponse(
    val result: AlbumDetail? = null
)

data class AlbumDetail(
    val id: Long = 0,
    val title: String? = null,
    val coverUri: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val trackCount: Int = 0,
    val artists: List<Artist>? = null,
    val volumes: List<List<Track>>? = null,
    val tracks: List<Track>? = null
)

data class ArtistBriefResponse(
    val result: ArtistBrief? = null
)

data class ArtistBrief(
    val artist: Artist? = null
)

data class ArtistTracksResponse(
    val result: com.google.gson.JsonElement? = null
)

data class ArtistAlbumsResponse(
    val result: com.google.gson.JsonElement? = null
)

data class RotorResponse(
    val result: RotorResult? = null
)

data class RotorResult(
    val sequence: List<RotorItem>? = null
)

data class RotorItem(
    val type: String? = null,
    val track: Track? = null
)

/*
 * Новый API ротора (сессии). В отличие от старого GET-эндпоинта станции,
 * он принимает очередь уже прослушанного и возвращает порции без повторов.
 * Ответы приходят без обёртки `result` — поля лежат на верхнем уровне.
 */

data class RotorSessionRequest(
    val seeds: List<String>,
    val queue: List<String>? = null,
    val includeTracksInResponse: Boolean = true
)

data class RotorSessionResponse(
    val radioSessionId: String? = null,
    val batchId: String? = null,
    val sequence: List<RotorItem>? = null,
    val terminated: Boolean = false,
    val interactive: Boolean = false
)

data class RotorSessionTracksRequest(
    val queue: List<String>? = null
)

data class RotorSessionTracksResponse(
    val batchId: String? = null,
    val sequence: List<RotorItem>? = null,
    val terminated: Boolean = false
)

data class RotorFeedbackRequest(
    val event: RotorFeedbackEvent,
    val batchId: String? = null
)

data class RotorFeedbackEvent(
    val type: String,
    val timestamp: String,
    val trackId: String? = null,
    val totalPlayedSeconds: Int? = null
)

data class FileInfoResponse(
    val result: FileInfoResult? = null
)

data class FileInfoResult(
    val downloadInfo: DownloadInfo? = null
)

data class DownloadInfo(
    val trackId: String? = null,
    val quality: String? = null,
    val codec: String? = null,
    val bitrate: Int = 0,
    val transport: String? = null,
    val key: String? = null,
    val url: String? = null,
    val urls: List<String>? = null,
    val realId: String? = null
)

package ru.yamus.app.core.api

import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import ru.yamus.app.core.models.AccountAboutResponse
import ru.yamus.app.core.models.AlbumWithTracksResponse
import ru.yamus.app.core.models.ArtistAlbumsResponse
import ru.yamus.app.core.models.ArtistBriefResponse
import ru.yamus.app.core.models.ArtistTracksResponse
import ru.yamus.app.core.models.LikeIdsResponse
import ru.yamus.app.core.models.PlaylistResponse
import ru.yamus.app.core.models.PlaylistsListResponse
import ru.yamus.app.core.models.RotorFeedbackRequest
import ru.yamus.app.core.models.RotorResponse
import ru.yamus.app.core.models.RotorSessionRequest
import ru.yamus.app.core.models.RotorSessionResponse
import ru.yamus.app.core.models.RotorSessionTracksRequest
import ru.yamus.app.core.models.RotorSessionTracksResponse
import ru.yamus.app.core.models.SearchResponse
import ru.yamus.app.core.models.TracksResponse

interface YandexApi {

    @GET("account/about")
    suspend fun getAccountAbout(): AccountAboutResponse

    @GET("users/{uid}/playlists/list")
    suspend fun getPlaylists(@Path("uid") uid: Long): PlaylistsListResponse

    @GET("users/{uid}/playlists/{kind}")
    suspend fun getPlaylist(
        @Path("uid") uid: Long,
        @Path("kind") kind: Long
    ): PlaylistResponse

    /** API принимает в пути как uid, так и логин владельца. */
    @GET("users/{user}/playlists/{kind}")
    suspend fun getPlaylistByUser(
        @Path("user") user: String,
        @Path("kind") kind: Long
    ): PlaylistResponse

    @GET("users/{uid}/likes/tracks")
    suspend fun getLikedIds(@Path("uid") uid: Long): LikeIdsResponse

    @GET("tracks")
    suspend fun getTracks(@Query("trackIds") trackIds: String): TracksResponse

    @POST("users/{uid}/likes/tracks/add")
    suspend fun likeTrack(
        @Path("uid") uid: Long,
        @Query("trackId") trackId: String
    ): ResponseBody

    @POST("users/{uid}/likes/tracks/remove")
    suspend fun unlikeTrack(
        @Path("uid") uid: Long,
        @Query("trackIds") trackId: String
    ): ResponseBody

    @GET("users/{uid}/dislikes/tracks")
    suspend fun getDislikedIds(@Path("uid") uid: Long): LikeIdsResponse

    @POST("users/{uid}/dislikes/tracks/add")
    suspend fun dislikeTrack(
        @Path("uid") uid: Long,
        @Query("trackId") trackId: String
    ): ResponseBody

    @POST("users/{uid}/dislikes/tracks/remove")
    suspend fun undislikeTrack(
        @Path("uid") uid: Long,
        @Query("trackIds") trackId: String
    ): ResponseBody

    @GET("search")
    suspend fun search(
        @Query("text") text: String,
        @Query("type") type: String,
        @Query("page") page: Int = 0,
        @Query("pageSize") pageSize: Int = 40,
        @Query("nocorrect") nocorrect: Boolean = false
    ): SearchResponse

    @GET("albums/{id}/with-tracks")
    suspend fun getAlbumWithTracks(@Path("id") id: Long): AlbumWithTracksResponse

    @GET("artists/{id}/brief-info")
    suspend fun getArtistBrief(@Path("id") id: Long): ArtistBriefResponse

    @GET("artists/{id}/tracks")
    suspend fun getArtistTracks(
        @Path("id") id: Long,
        @Query("page") page: Int = 0,
        @Query("pageSize") pageSize: Int = 10
    ): ArtistTracksResponse

    @GET("artists/{id}/direct-albums")
    suspend fun getArtistAlbums(@Path("id") id: Long): ArtistAlbumsResponse

    @GET("rotor/station/{station}/tracks")
    suspend fun getRotorTracks(
        @Path(value = "station", encoded = true) station: String
    ): RotorResponse

    /**
     * Новая сессия ротора. `queue` — уже прослушанные треки: сервер не
     * повторяет их в следующих порциях. Заголовки фронтенда нужны, чтобы
     * API включило режим нового клиента, который поддерживает сессии.
     */
    @Headers(
        "X-Yandex-Music-Frontend: new",
        "X-Yandex-Music-Without-Invocation-Info: 1"
    )
    @POST("rotor/session/new")
    suspend fun createRotorSession(
        @Body body: RotorSessionRequest
    ): RotorSessionResponse

    @Headers(
        "X-Yandex-Music-Frontend: new",
        "X-Yandex-Music-Without-Invocation-Info: 1"
    )
    @POST("rotor/session/{sessionId}/tracks")
    suspend fun getRotorSessionTracks(
        @Path("sessionId") sessionId: String,
        @Body body: RotorSessionTracksRequest
    ): RotorSessionTracksResponse

    @Headers(
        "X-Yandex-Music-Frontend: new",
        "X-Yandex-Music-Without-Invocation-Info: 1"
    )
    @POST("rotor/session/{sessionId}/feedback")
    suspend fun sendRotorSessionFeedback(
        @Path("sessionId") sessionId: String,
        @Body body: RotorFeedbackRequest
    ): ResponseBody

    @FormUrlEncoded
    @POST("users/{uid}/playlists/{kind}/change-relative")
    suspend fun changePlaylistRelative(
        @Path("uid") uid: Long,
        @Path("kind") kind: Long,
        @Field("diff") diff: String,
        @Field("revision") revision: String
    ): PlaylistResponse
}

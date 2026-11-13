package ru.yamus.app.ui.album

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.yamus.app.R
import ru.yamus.app.core.api.ApiClient
import ru.yamus.app.core.models.Track
import ru.yamus.app.core.player.PlaybackManager
import ru.yamus.app.core.util.toCoverUrl
import ru.yamus.app.ui.common.BaseActivity
import ru.yamus.app.ui.common.MenuDialog
import ru.yamus.app.ui.common.Navigation
import ru.yamus.app.ui.common.ShareLink
import ru.yamus.app.ui.main.MainActivity

class AlbumActivity : BaseActivity() {

    companion object {
        private const val EXTRA_ID = "album_id"
        private const val EXTRA_TRACK_ID = "track_id"
        private const val EXTRA_TITLE = "album_title"

        fun intent(
            context: Context,
            albumId: Long,
            title: String?,
            trackId: String? = null
        ): Intent =
            Intent(context, AlbumActivity::class.java)
                .putExtra(EXTRA_ID, albumId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TRACK_ID, trackId)

        /** Ссылка на трек: альбом определяется по самому треку. */
        fun trackIntent(context: Context, trackId: String, title: String? = null): Intent =
            Intent(context, AlbumActivity::class.java)
                .putExtra(EXTRA_TRACK_ID, trackId)
                .putExtra(EXTRA_TITLE, title)
    }

    private lateinit var toolbarTitle: TextView
    private lateinit var progress: ProgressBar
    private lateinit var adapter: AlbumPageAdapter
    private lateinit var list: RecyclerView

    private var tracks: List<Track> = emptyList()
    private var albumId = 0L
    private var artistId = 0L
    private var artistName: String? = null

    /** Трек из ссылки: после загрузки прокручиваем к нему и подсвечиваем. */
    private var highlightTrackId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_album)
        installPlayerSheet()
        installChrome(-1) { MainActivity.open(this, it) }

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.moreButton).setOnClickListener { showOptions() }
        toolbarTitle = findViewById(R.id.toolbarTitle)
        progress = findViewById(R.id.progress)

        val albumId = intent.getLongExtra(EXTRA_ID, 0L)
        val trackId = intent.getStringExtra(EXTRA_TRACK_ID)
        highlightTrackId = trackId?.takeIf { it.isNotBlank() }
        if (albumId <= 0 && trackId.isNullOrBlank()) {
            finish()
            return
        }
        if (albumId > 0) this.albumId = albumId

        val knownTitle = intent.getStringExtra(EXTRA_TITLE)
            ?: getString(R.string.albums)
        toolbarTitle.text = knownTitle

        adapter = AlbumPageAdapter(
            onPlayTrack = { index ->
                if (tracks.isNotEmpty()) PlaybackManager.playQueue(tracks, index)
            }
        )
        adapter.renderHeader(null, knownTitle, "", "")

        list = findViewById(R.id.contentList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        if (albumId > 0) {
            load(albumId)
        } else {
            trackId?.let { loadAlbumForTrack(it) }
        }
    }

    private fun showOptions() {
        val groups = ArrayList<List<MenuDialog.Item>>()

        if (tracks.isNotEmpty()) {
            groups.add(
                listOf(
                    MenuDialog.Item(getString(R.string.album_play)) {
                        PlaybackManager.playQueue(tracks, 0)
                    }
                )
            )
        }

        groups.add(
            listOf(
                MenuDialog.Item(getString(R.string.track_share)) {
                    ShareLink.copy(this, ShareLink.album(albumId))
                }
            )
        )

        if (artistId > 0) {
            groups.add(
                listOf(
                    MenuDialog.Item(getString(R.string.track_artist)) {
                        Navigation.openArtist(this, artistId, artistName)
                    }
                )
            )
        }

        MenuDialog.show(this, toolbarTitle.text, groups)
    }

    /**
     * Ссылка вида /track/{id} не содержит альбома. Запрашиваем трек и берём
     * альбом из ответа, а если API его не вернул — вторую часть составного
     * идентификатора трека.
     */
    private fun loadAlbumForTrack(trackId: String) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val track = try {
                withContext(Dispatchers.IO) {
                    ApiClient.api.getTracks(trackId).result?.firstOrNull()
                }
            } catch (_: Exception) {
                null
            }
            if (isFinishing) return@launch

            val album = track?.albums?.firstOrNull { it.id > 0 }
            val resolvedId = album?.id
                ?: track?.id?.substringAfter(':', "")?.toLongOrNull()
                ?: trackId.substringAfter(':', "").toLongOrNull()

            if (resolvedId == null || resolvedId <= 0) {
                progress.visibility = View.GONE
                PlaybackManager.toast(getString(R.string.link_album_not_found))
                finish()
                return@launch
            }

            album?.title?.takeIf { it.isNotBlank() }?.let { toolbarTitle.text = it }
            // Полный id трека из API точнее короткого из ссылки.
            track?.id?.takeIf { it.isNotBlank() }?.let { highlightTrackId = it }
            load(resolvedId)
        }
    }

    private fun load(albumId: Long) {
        this.albumId = albumId
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val detail = try {
                withContext(Dispatchers.IO) {
                    ApiClient.api.getAlbumWithTracks(albumId).result
                }
            } catch (_: Exception) {
                null
            }
            if (isFinishing) return@launch
            progress.visibility = View.GONE

            if (detail == null) {
                PlaybackManager.toast("Не удалось загрузить альбом")
                return@launch
            }

            val loaded = ArrayList<Track>()
            detail.volumes?.forEach { disc -> loaded.addAll(disc) }
            if (loaded.isEmpty()) detail.tracks?.let { loaded.addAll(it) }
            tracks = loaded

            val title = detail.title ?: getString(R.string.albums)
            toolbarTitle.text = title
            detail.artists?.firstOrNull { it.id > 0 }?.let {
                artistId = it.id
                artistName = it.name
            }
            val artistNames = detail.artists.orEmpty()
                .mapNotNull { it.name?.takeIf { name -> name.isNotBlank() } }
            val artists = if (artistNames.isEmpty()) {
                "Неизвестный исполнитель"
            } else {
                artistNames.joinToString(", ")
            }
            val meta = StringBuilder()
            detail.year?.let { meta.append(it) }
            if (meta.isNotEmpty()) meta.append(" • ")
            meta.append(getString(R.string.tracks_count, tracks.size))

            adapter.renderHeader(
                detail.coverUri.toCoverUrl(300),
                title,
                artists,
                meta.toString()
            )
            adapter.renderTracks(tracks, highlightTrackId)
            scrollToHighlighted()
        }
    }

    /**
     * Ссылка на трек должна сразу показать его в списке: прокручиваем
     * к строке и оставляем акцентную рамку, чтобы не искать глазами.
     */
    private fun scrollToHighlighted() {
        val highlighted = highlightTrackId ?: return
        val position = adapter.positionOfTrack(highlighted)
        if (position < 0) return
        list.post {
            if (isFinishing) return@post
            (list.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(position, dp(8))
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}

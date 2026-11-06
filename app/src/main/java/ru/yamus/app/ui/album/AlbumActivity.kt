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
        private const val EXTRA_TITLE = "album_title"

        fun intent(context: Context, albumId: Long, title: String?): Intent =
            Intent(context, AlbumActivity::class.java)
                .putExtra(EXTRA_ID, albumId)
                .putExtra(EXTRA_TITLE, title)
    }

    private lateinit var toolbarTitle: TextView
    private lateinit var progress: ProgressBar
    private lateinit var adapter: AlbumPageAdapter

    private var tracks: List<Track> = emptyList()
    private var albumId = 0L
    private var artistId = 0L
    private var artistName: String? = null

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
        if (albumId <= 0) {
            finish()
            return
        }
        this.albumId = albumId

        val knownTitle = intent.getStringExtra(EXTRA_TITLE)
            ?: getString(R.string.albums)
        toolbarTitle.text = knownTitle

        adapter = AlbumPageAdapter(
            onPlayTrack = { index ->
                if (tracks.isNotEmpty()) PlaybackManager.playQueue(tracks, index)
            }
        )
        adapter.renderHeader(null, knownTitle, "", "")

        val list = findViewById<RecyclerView>(R.id.contentList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        load(albumId)
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

    private fun load(albumId: Long) {
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
            adapter.renderTracks(tracks)
        }
    }
}

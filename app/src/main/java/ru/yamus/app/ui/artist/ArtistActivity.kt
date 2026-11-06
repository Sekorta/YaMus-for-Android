package ru.yamus.app.ui.artist

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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.yamus.app.R
import ru.yamus.app.core.api.ApiClient
import ru.yamus.app.core.data.ArtistContent
import ru.yamus.app.core.models.Track
import ru.yamus.app.core.player.PlaybackManager
import ru.yamus.app.core.util.imageUrl
import ru.yamus.app.ui.common.BaseActivity
import ru.yamus.app.ui.common.MenuDialog
import ru.yamus.app.ui.common.Navigation
import ru.yamus.app.ui.common.ShareLink
import ru.yamus.app.ui.main.MainActivity

class ArtistActivity : BaseActivity() {

    companion object {
        private const val EXTRA_ID = "artist_id"
        private const val EXTRA_NAME = "artist_name"

        fun intent(context: Context, artistId: Long, name: String?): Intent =
            Intent(context, ArtistActivity::class.java)
                .putExtra(EXTRA_ID, artistId)
                .putExtra(EXTRA_NAME, name)
    }

    private lateinit var toolbarTitle: TextView
    private lateinit var progress: ProgressBar
    private lateinit var adapter: ArtistPageAdapter

    private var tracks: List<Track> = emptyList()
    private var avatarUrl: String? = null
    private var allTracksCount = 0
    private var artistId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_artist)
        installPlayerSheet()
        installChrome(-1) { MainActivity.open(this, it) }

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.moreButton).setOnClickListener { showOptions() }
        toolbarTitle = findViewById(R.id.toolbarTitle)
        progress = findViewById(R.id.progress)

        artistId = intent.getLongExtra(EXTRA_ID, 0L)
        if (artistId <= 0) {
            finish()
            return
        }

        val knownName = intent.getStringExtra(EXTRA_NAME)
            ?: getString(R.string.search_artists)
        toolbarTitle.text = knownName

        adapter = ArtistPageAdapter(
            onPlayTrack = { index ->
                if (tracks.isNotEmpty()) PlaybackManager.playQueue(tracks, index)
            },
            onOpenAlbum = { album ->
                Navigation.openAlbum(this, album.id, album.title)
            },
            onOpenAllTracks = { openAllTracks() }
        )
        adapter.renderHeader(knownName, null, "")

        val list = findViewById<RecyclerView>(R.id.contentList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        load(artistId)
    }

    private fun showOptions() {
        val groups = ArrayList<List<MenuDialog.Item>>()

        if (tracks.isNotEmpty() || allTracksCount > 0) {
            groups.add(
                listOf(
                    MenuDialog.Item(getString(R.string.all_tracks)) { openAllTracks() }
                )
            )
        }

        groups.add(
            listOf(
                MenuDialog.Item(getString(R.string.track_share)) {
                    ShareLink.copy(this, ShareLink.artist(artistId))
                }
            )
        )

        MenuDialog.show(this, toolbarTitle.text, groups)
    }

    private fun openAllTracks() {
        startActivity(
            ArtistTracksActivity.intent(
                this,
                artistId,
                toolbarTitle.text.toString(),
                avatarUrl,
                allTracksCount
            )
        )
    }

    private fun load(artistId: Long) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                coroutineScope {
                    val briefDeferred = async {
                        runCatching { ApiClient.api.getArtistBrief(artistId).result?.artist }.getOrNull()
                    }
                    val tracksDeferred = async {
                        runCatching {
                            ArtistContent.parseTracks(
                                ApiClient.api.getArtistTracks(artistId).result
                            )
                        }.getOrDefault(emptyList())
                    }
                    val albumsDeferred = async {
                        runCatching {
                            ArtistContent.parseAlbums(
                                ApiClient.api.getArtistAlbums(artistId).result
                            )
                        }.getOrDefault(emptyList())
                    }
                    Triple(briefDeferred.await(), tracksDeferred.await(), albumsDeferred.await())
                }
            }
            if (isFinishing) return@launch
            progress.visibility = View.GONE

            val brief = data.first
            tracks = data.second
            val albums = data.third

            val name = brief?.name
                ?: intent.getStringExtra(EXTRA_NAME)
                ?: getString(R.string.search_artists)
            toolbarTitle.text = name
            avatarUrl = brief?.imageUrl(200)
            allTracksCount = brief?.counts?.tracks?.takeIf { it > 0 } ?: tracks.size

            adapter.renderHeader(
                name,
                avatarUrl,
                getString(R.string.tracks_count, allTracksCount)
            )
            adapter.renderContent(tracks, albums)

            if (tracks.isEmpty() && albums.isEmpty()) {
                PlaybackManager.toast("Не удалось загрузить исполнителя")
            }
        }
    }
}

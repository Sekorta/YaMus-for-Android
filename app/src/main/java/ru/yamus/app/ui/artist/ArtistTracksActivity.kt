package ru.yamus.app.ui.artist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch
import ru.yamus.app.R
import ru.yamus.app.core.data.ArtistContent
import ru.yamus.app.core.models.Track
import ru.yamus.app.core.player.PlaybackManager
import ru.yamus.app.ui.common.BaseActivity
import ru.yamus.app.ui.common.TrackAdapter
import ru.yamus.app.ui.common.avatarOptions
import ru.yamus.app.ui.common.setCircleBackground
import ru.yamus.app.ui.main.MainActivity

/** Полный список треков исполнителя («Все треки»). */
class ArtistTracksActivity : BaseActivity() {

    companion object {
        private const val EXTRA_ID = "artist_id"
        private const val EXTRA_NAME = "artist_name"
        private const val EXTRA_AVATAR = "artist_avatar"
        private const val EXTRA_COUNT = "artist_tracks_count"

        fun intent(
            context: Context,
            artistId: Long,
            name: String?,
            avatar: String?,
            totalCount: Int
        ): Intent = Intent(context, ArtistTracksActivity::class.java)
            .putExtra(EXTRA_ID, artistId)
            .putExtra(EXTRA_NAME, name)
            .putExtra(EXTRA_AVATAR, avatar)
            .putExtra(EXTRA_COUNT, totalCount)
    }

    private lateinit var progress: ProgressBar
    private lateinit var meta: TextView
    private lateinit var adapter: TrackAdapter

    private var tracks: List<Track> = emptyList()
    private var queueTag = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_artist_tracks)
        installPlayerSheet()
        installChrome(-1) { MainActivity.open(this, it) }

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        progress = findViewById(R.id.progress)
        meta = findViewById(R.id.tracksMeta)
        val list = findViewById<RecyclerView>(R.id.tracksList)
        val name = intent.getStringExtra(EXTRA_NAME) ?: getString(R.string.search_artists)
        val total = intent.getIntExtra(EXTRA_COUNT, 0)
        val artistId = intent.getLongExtra(EXTRA_ID, 0L)
        queueTag = "artist-tracks:$artistId"

        findViewById<TextView>(R.id.toolbarTitle).text = getString(R.string.all_tracks)
        findViewById<TextView>(R.id.artistName).text = name
        meta.text = getString(R.string.tracks_count, total)

        val avatar = findViewById<ImageView>(R.id.avatar)
        findViewById<View>(R.id.avatarCircle).setCircleBackground(this, R.attr.yamusCard)
        val avatarUrl = intent.getStringExtra(EXTRA_AVATAR)
        if (!avatarUrl.isNullOrBlank()) {
            Glide.with(this)
                .load(avatarUrl)
                .apply(avatarOptions(this))
                .into(avatar)
        }

        adapter = TrackAdapter { _, index ->
            if (tracks.isNotEmpty()) {
                PlaybackManager.playQueue(
                    tracks,
                    index.coerceIn(0, tracks.size - 1),
                    tag = queueTag
                )
            }
        }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        if (artistId <= 0) {
            finish()
            return
        }
        load(artistId, total)
    }

    private fun load(artistId: Long, total: Int) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            ArtistContent.loadAllTracks(artistId, total) { partial ->
                if (isFinishing) return@loadAllTracks
                tracks = partial
                adapter.submit(partial)
                progress.visibility = View.GONE
                meta.text = getString(R.string.tracks_count, partial.size)
                PlaybackManager.replaceQueue(queueTag, partial)
            }
            progress.visibility = View.GONE
            // Могли начать слушать первые 200 треков — дополняем очередь.
            PlaybackManager.replaceQueue(queueTag, tracks)
            if (tracks.isEmpty()) {
                PlaybackManager.toast("Не удалось загрузить треки исполнителя")
            }
        }
    }
}

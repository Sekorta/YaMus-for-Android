package ru.yamus.app.ui.playlist

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.yamus.app.R
import ru.yamus.app.core.api.ApiClient
import ru.yamus.app.core.data.AccountStore
import ru.yamus.app.core.data.LikesStore
import ru.yamus.app.core.data.TracksLoader
import ru.yamus.app.core.models.Track
import ru.yamus.app.core.player.PlaybackManager
import ru.yamus.app.core.util.toCoverUrl
import ru.yamus.app.ui.common.BaseActivity
import ru.yamus.app.ui.common.IconView
import ru.yamus.app.ui.common.TrackAdapter
import ru.yamus.app.ui.main.MainActivity

class PlaylistActivity : BaseActivity() {

    companion object {
        private const val EXTRA_UID = "uid"
        private const val EXTRA_USER = "user"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_COVER = "cover"
        private const val EXTRA_LIKED = "liked"

        fun intent(
            context: Context,
            uid: Long,
            kind: Long,
            title: String?,
            cover: String? = null
        ): Intent = Intent(context, PlaylistActivity::class.java)
            .putExtra(EXTRA_UID, uid)
            .putExtra(EXTRA_KIND, kind)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_COVER, cover)
            .putExtra(EXTRA_LIKED, false)

        /** Ссылка на плейлист: [user] — логин или uid, null — свой плейлист. */
        fun userIntent(context: Context, user: String?, kind: Long): Intent =
            Intent(context, PlaylistActivity::class.java)
                .putExtra(EXTRA_USER, user)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_LIKED, false)

        fun likedIntent(context: Context, uid: Long): Intent =
            Intent(context, PlaylistActivity::class.java)
                .putExtra(EXTRA_UID, uid)
                .putExtra(EXTRA_LIKED, true)
    }

    private lateinit var toolbarTitle: TextView
    private lateinit var cover: ImageView
    private lateinit var coverFallback: IconView
    private lateinit var titleView: TextView
    private lateinit var metaView: TextView
    private lateinit var list: RecyclerView
    private lateinit var progress: ProgressBar

    private lateinit var adapter: TrackAdapter
    private var tracks: List<Track> = emptyList()
    private var likedMode = false
    private var queueTag = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist)
        installPlayerSheet()
        installChrome(-1) { MainActivity.open(this, it) }

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        toolbarTitle = findViewById(R.id.toolbarTitle)
        cover = findViewById(R.id.cover)
        coverFallback = findViewById(R.id.coverFallback)
        titleView = findViewById(R.id.playlistTitle)
        metaView = findViewById(R.id.playlistMeta)
        list = findViewById(R.id.tracksList)
        progress = findViewById(R.id.progress)

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

        val uid = intent.getLongExtra(EXTRA_UID, 0L)
        val liked = intent.getBooleanExtra(EXTRA_LIKED, false)
        val kind = intent.getLongExtra(EXTRA_KIND, 0L)
        val user = intent.getStringExtra(EXTRA_USER)?.takeIf { it.isNotBlank() }
        queueTag = if (liked) "liked:$uid" else "playlist:$uid:$kind"

        if (liked) {
            loadLiked(uid)
        } else {
            if (kind <= 0) {
                finish()
                return
            }
            intent.getStringExtra(EXTRA_TITLE)?.let {
                titleView.text = it
                toolbarTitle.text = it
            }
            loadCover(intent.getStringExtra(EXTRA_COVER))
            when {
                uid > 0 -> loadPlaylist(uid, kind)
                user != null -> loadPlaylistForUser(user, kind)
                else -> loadOwnPlaylist(kind)
            }
        }
    }

    private fun loadLiked(uid: Long) {
        likedMode = true
        coverFallback.setImageResource(R.drawable.ic_heart)
        cover.visibility = View.GONE
        titleView.text = getString(R.string.liked_tracks)
        toolbarTitle.text = getString(R.string.liked_tracks)
        progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            if (!LikesStore.loaded && uid > 0) {
                LikesStore.load(uid)
            }
            val ids = LikesStore.idsSnapshot()
            if (ids.isEmpty()) {
                progress.visibility = View.GONE
                showTracks(emptyList(), 0, final = true)
                return@launch
            }

            metaView.text = getString(R.string.tracks_count, ids.size)
            val loaded = TracksLoader.loadByIds(ids) { partial ->
                if (isFinishing) return@loadByIds
                progress.visibility = View.GONE
                showTracks(partial, ids.size, final = false)
            }
            progress.visibility = View.GONE
            showTracks(loaded, ids.size, final = true)
        }
    }

    /** Ссылка без логина: плейлист текущего пользователя. */
    private fun loadOwnPlaylist(kind: Long) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val uid = AccountStore.uid.takeIf { it > 0 }
                ?: AccountStore.load()?.uid
            if (isFinishing) return@launch

            if (uid == null || uid <= 0) {
                progress.visibility = View.GONE
                PlaybackManager.toast(getString(R.string.link_user_not_found))
                finish()
                return@launch
            }
            loadPlaylist(uid, kind)
        }
    }

    /** В ссылке владелец может быть логином: API принимает его прямо в пути. */
    private fun loadPlaylistForUser(user: String, kind: Long) {
        val uid = user.toLongOrNull()
        if (uid != null && uid > 0) {
            loadPlaylist(uid, kind)
        } else {
            loadPlaylist(user, kind)
        }
    }

    private fun loadPlaylist(uid: Long, kind: Long) = loadPlaylist(uid.toString(), kind)

    private fun loadPlaylist(owner: String, kind: Long) {
        queueTag = "playlist:$owner:$kind"
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val detail = try {
                withContext(Dispatchers.IO) {
                    ApiClient.api.getPlaylistByUser(owner, kind).result
                }
            } catch (_: Exception) {
                null
            }

            if (detail == null) {
                progress.visibility = View.GONE
                PlaybackManager.toast("Не удалось загрузить плейлист")
                return@launch
            }

            detail.owner?.uid?.takeIf { it > 0 }?.let {
                queueTag = "playlist:$it:$kind"
            }

            val items = detail.tracks.orEmpty()
            titleView.text = detail.title ?: getString(R.string.playlists)
            toolbarTitle.text = detail.title ?: getString(R.string.playlists)

            val owner = detail.owner?.name
                ?: detail.owner?.login
                ?: "Яндекс Музыка"
            metaView.text = "$owner • ${getString(R.string.tracks_count, items.size)}"

            val detailCover = detail.cover?.uri.toCoverUrl(400)
            if (!detailCover.isNullOrBlank()) {
                loadCover(detailCover)
            }

            // Часть ответа уже содержит полные треки, остальные догружаем
            // параллельно и показываем список по мере поступления.
            val byId = HashMap<String, Track>()
            items.forEach { item ->
                item.track?.takeIf { it.id.isNotBlank() }?.let { byId[it.id] = it }
            }
            val missingIds = items
                .filter { it.track == null }
                .mapNotNull { it.id?.takeIf { id -> id.isNotBlank() } }
                .distinct()

            fun ordered(): List<Track> = items.mapNotNull { item ->
                item.track ?: item.id?.let { byId[it] }
            }

            // Часть треков пришла прямо в ответе — показываем сразу.
            if (byId.isNotEmpty() && missingIds.isNotEmpty()) {
                progress.visibility = View.GONE
                showTracks(ordered(), items.size, final = false)
            }

            if (missingIds.isEmpty()) {
                progress.visibility = View.GONE
                showTracks(ordered(), items.size, final = true)
                return@launch
            }

            val loaded = TracksLoader.loadByIds(missingIds) { partial ->
                if (isFinishing) return@loadByIds
                partial.forEach { byId[it.id] = it }
                progress.visibility = View.GONE
                showTracks(ordered(), items.size, final = false)
            }
            loaded.forEach { byId[it.id] = it }
            progress.visibility = View.GONE
            showTracks(ordered(), items.size, final = true)
        }
    }

    private fun loadCover(url: String?) {
        if (url.isNullOrBlank()) return
        cover.visibility = View.VISIBLE
        Glide.with(this).load(url).into(cover)
    }

    private fun showTracks(loaded: List<Track>, totalCount: Int, final: Boolean) {
        tracks = loaded
        adapter.submit(loaded)
        if (likedMode) {
            metaView.text = getString(R.string.tracks_count, totalCount)
        }
        // Дополняем уже играющую очередь по мере догрузки.
        PlaybackManager.replaceQueue(queueTag, loaded)
        if (final && loaded.isEmpty()) {
            PlaybackManager.toast("Здесь пока нет треков")
        }
    }
}

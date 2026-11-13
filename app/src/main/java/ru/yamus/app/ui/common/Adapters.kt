package ru.yamus.app.ui.common

import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import ru.yamus.app.R
import ru.yamus.app.core.download.OfflineLibrary
import ru.yamus.app.core.models.Album
import ru.yamus.app.core.models.Artist
import ru.yamus.app.core.models.PlaylistSummary
import ru.yamus.app.core.models.Track
import ru.yamus.app.core.player.PlaybackManager
import ru.yamus.app.core.util.artistText
import ru.yamus.app.core.util.coverUrl
import ru.yamus.app.core.util.displayTitle
import ru.yamus.app.core.util.dp
import ru.yamus.app.core.util.formatDuration
import ru.yamus.app.core.util.imageUrl
import ru.yamus.app.core.util.ownerName
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

/** Заполнение строки трека и общие действия для неё. */
object TrackRowBinder {

    private val rows: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Найденные однажды view строки. findViewById обходит всё дерево и на
     * старых телефонах при прокрутке заметно съедает кадровое время, поэтому
     * держим ссылки в теге строки — она живёт столько же, сколько view.
     */
    private class Views(
        val title: TextView,
        val artist: TextView,
        val duration: TextView,
        val cover: ImageView,
        val playing: IconView?,
        val offline: IconView?,
        val format: TextView?,
        val more: View
    ) {
        var boundTrack: Track? = null
        var boundCoverUrl: String? = null
        var boundTrackId: String? = null
        var boundCurrent: Boolean? = null
        var boundAccent: Boolean? = null
        var boundOffline: Boolean? = null
        var boundFormat: String? = null
        var boundHighlight: Boolean? = null
    }

    private fun views(row: View): Views {
        (row.getTag(R.id.track_row_views) as? Views)?.let { return it }
        val created = Views(
            row.findViewById(R.id.trackTitle),
            row.findViewById(R.id.trackArtist),
            row.findViewById(R.id.trackDuration),
            row.findViewById(R.id.trackCover),
            row.findViewById(R.id.trackPlaying),
            row.findViewById(R.id.trackOffline),
            row.findViewById(R.id.trackFormat),
            row.findViewById(R.id.trackMore)
        )
        row.setTag(R.id.track_row_views, created)
        return created
    }

    init {
        PlaybackManager.addListener(object : PlaybackManager.Listener {
            override fun onPlaybackChanged() {
                handler.post { refreshPlayingRows() }
            }
        })
        // Скачивание завершилось или трек удалили — обновляем пометки в
        // видимых строках, не дожидаясь пересоздания списка.
        OfflineLibrary.addListener(object : OfflineLibrary.Listener {
            override fun onLibraryChanged() {
                handler.post { refreshOfflineRows() }
            }
        })
    }

    fun inflate(parent: ViewGroup): View =
        LayoutInflater.from(parent.context)
            .inflate(R.layout.item_track, parent, false)
            .also { applyTerminalTypeface(it) }

    fun bind(
        row: View,
        track: Track,
        highlight: Boolean = false,
        onPlay: () -> Unit
    ) {
        val v = views(row)
        // RecyclerView часто отдаёт ту же строку тому же треку (прокрутка
        // туда-обратно). Повторно форматировать и грузить картинку незачем.
        if (v.boundTrack !== track) {
            v.boundTrack = track
            v.title.text = track.displayTitle()
            v.artist.text = track.artistText()
            v.duration.text = formatDuration(track.durationMs)
        }
        applyOfflineState(row, track)
        applyHighlight(row, highlight)
        updateCover(row, track)

        row.setTag(R.id.trackPlaying, track.id)
        synchronized(rows) { rows.add(row) }
        updatePlayingState(row, track.id)

        row.setOnClickListener { onPlay() }
        v.more.setOnClickListener {
            TrackOptionsDialog.show(row.context, track)
        }
    }

    /** Пометка «скачано» и формат файла для строки трека. */
    private fun applyOfflineState(row: View, track: Track) {
        val v = views(row)
        val entry = OfflineLibrary.find(track)
        val format = entry?.format?.takeIf { it.isNotBlank() }
        val offline = format != null
        if (v.boundOffline == offline && v.boundFormat == format) return
        v.boundOffline = offline
        v.boundFormat = format
        v.offline?.visibility = if (offline) View.VISIBLE else View.GONE
        v.format?.let { label ->
            label.visibility = if (offline) View.VISIBLE else View.GONE
            label.text = format
        }
    }

    private fun updateCover(row: View, track: Track) {
        val v = views(row)
        val cover = v.cover
        // У скачанного трека обложка встроена в файл. Пока она извлекается,
        // показываем сетевую — так строка не остаётся пустой.
        val offlineCover = OfflineLibrary.coverModel(row.context, track) as? File
        val url = track.coverUrl(200)
        val key = offlineCover?.absolutePath ?: url
        if (v.boundCoverUrl == key) return
        v.boundCoverUrl = key
        val model: Any? = offlineCover ?: url
        if (model == null) {
            Glide.with(row).clear(cover)
            cover.setPadding(
                row.context.dp(12), row.context.dp(12),
                row.context.dp(12), row.context.dp(12)
            )
            cover.scaleType = ImageView.ScaleType.FIT_CENTER
            cover.setImageDrawable(AppCompatResources.getDrawable(row.context, R.drawable.ic_music))
        } else {
            cover.setPadding(0, 0, 0, 0)
            cover.scaleType = ImageView.ScaleType.CENTER_CROP
            Glide.with(row)
                .load(model)
                .apply(coverOptions(row.context, 6))
                .into(cover)
        }
    }

    /**
     * Акцентная рамка вокруг строки, к которой ведёт ссылка на трек.
     * Фон строки в остальное время — обычный ripple.
     */
    private fun applyHighlight(row: View, highlight: Boolean) {
        val v = views(row)
        if (v.boundHighlight == highlight) return
        v.boundHighlight = highlight
        if (highlight) {
            val accent = ThemeManager.color(row.context, R.attr.yamusAccent)
            val drawable = GradientDrawable().apply {
                setColor(ColorUtils.setAlphaComponent(accent, 26))
                setStroke(row.context.dp(2), accent)
            }
            row.background = drawable
        } else {
            val out = TypedValue()
            row.context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground, out, true
            )
            row.setBackgroundResource(out.resourceId)
        }
    }

    private fun refreshPlayingRows() {
        val snapshot = synchronized(rows) { rows.toList() }
        for (row in snapshot) {
            if (row.windowToken == null) continue
            updatePlayingState(row, row.getTag(R.id.trackPlaying) as? String)
        }
    }

    private fun refreshOfflineRows() {
        val snapshot = synchronized(rows) { rows.toList() }
        for (row in snapshot) {
            if (row.windowToken == null) continue
            val track = views(row).boundTrack ?: continue
            applyOfflineState(row, track)
            updateCover(row, track)
        }
    }

    private fun updatePlayingState(row: View, trackId: String?) {
        val v = views(row)
        val isCurrent = trackId != null && trackId == PlaybackManager.currentTrack?.id
        val playing = isCurrent && PlaybackManager.isPlaying
        // Кэшируем и «это текущий трек», и «играет»: при смене трека на
        // паузе второе значение не меняется, и без boundCurrent иконка
        // оставалась на прежней строке.
        if (v.boundTrackId == trackId &&
            v.boundCurrent == isCurrent &&
            v.boundAccent == playing
        ) {
            return
        }
        v.boundTrackId = trackId
        v.boundCurrent = isCurrent
        v.boundAccent = playing

        v.playing?.let { icon ->
            icon.visibility = if (isCurrent) View.VISIBLE else View.GONE
            icon.setTintColor(
                ThemeManager.color(
                    row.context,
                    if (playing) R.attr.yamusAccent else R.attr.yamusTextSub
                )
            )
        }

        v.title.setTextColor(
            ThemeManager.color(
                row.context,
                if (playing) R.attr.yamusAccent else R.attr.yamusText
            )
        )
    }

    /** Заполняет LinearLayout списком треков (альбом, артист, плейлист). */
    fun populate(container: LinearLayout, tracks: List<Track>, onPlay: (Int) -> Unit) {
        container.removeAllViews()
        container.addRowsInChunks(tracks.size) { index ->
            val row = inflate(container)
            bind(row, tracks[index]) { onPlay(index) }
            container.addView(row)
        }
    }
}

/**
 * Добавляет строки порциями. На старых телефонах построение сразу сотни
 * view-элементов подвешивает UI-поток на секунды, поэтому между порциями
 * отдаём управление циклу сообщений.
 */
fun LinearLayout.addRowsInChunks(
    count: Int,
    chunkSize: Int = 6,
    addRow: (index: Int) -> Unit
) {
    if (count <= 0) return
    val handler = Handler(Looper.getMainLooper())
    var index = 0
    fun step() {
        val end = minOf(index + chunkSize, count)
        while (index < end) {
            addRow(index)
            index++
        }
        if (index < count) handler.post { step() }
    }
    step()
}

class TrackAdapter(
    private val onClick: (Track, Int) -> Unit
) : RecyclerView.Adapter<TrackAdapter.Holder>() {

    private val items = ArrayList<Track>()

    fun submit(list: List<Track>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun items(): List<Track> = items

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(TrackRowBinder.inflate(parent))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val track = items.getOrNull(position) ?: return
        TrackRowBinder.bind(holder.itemView, track) { onClick(track, position) }
    }

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView)
}

class ArtistAdapter(
    private val onClick: (Artist) -> Unit
) : RecyclerView.Adapter<ArtistAdapter.Holder>() {

    private val items = ArrayList<Artist>()

    fun submit(list: List<Artist>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_artist, parent, false)
                .also { applyTerminalTypeface(it) }
        ).also { holder ->
            holder.itemView.findViewById<ImageView>(R.id.artistAvatar)
                .setCircleBackground(parent.context, R.attr.yamusCard)
        }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val artist = items.getOrNull(position) ?: return
        val row = holder.itemView
        row.findViewById<TextView>(R.id.artistName).text = artist.name ?: "Исполнитель"

        val counts = artist.counts
        val meta = StringBuilder()
        if (counts != null && counts.tracks > 0) meta.append("Треков: ").append(counts.tracks)
        if (counts != null && counts.directAlbums > 0) {
            if (meta.isNotEmpty()) meta.append(" • ")
            meta.append("Альбомов: ").append(counts.directAlbums)
        }
        row.findViewById<TextView>(R.id.artistMeta).text = meta.toString()

        val avatar = row.findViewById<ImageView>(R.id.artistAvatar)
        val url = artist.imageUrl(200)
        if (url.isNullOrBlank()) {
            Glide.with(row).clear(avatar)
            avatar.setPadding(row.context.dp(12), row.context.dp(12), row.context.dp(12), row.context.dp(12))
            avatar.scaleType = ImageView.ScaleType.FIT_CENTER
            avatar.setImageDrawable(AppCompatResources.getDrawable(row.context, R.drawable.ic_person))
        } else {
            avatar.setPadding(0, 0, 0, 0)
            avatar.scaleType = ImageView.ScaleType.CENTER_CROP
            Glide.with(row)
                .load(url)
                .apply(avatarOptions(row.context))
                .into(avatar)
        }

        row.setOnClickListener { onClick(artist) }
    }

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView)
}

object AlbumRowBinder {

    private class Views(
        val title: TextView,
        val meta: TextView,
        val cover: ImageView
    ) {
        var boundAlbum: Album? = null
        var boundCoverUrl: String? = null
    }

    private fun views(row: View): Views {
        (row.getTag(R.id.album_row_views) as? Views)?.let { return it }
        val created = Views(
            row.findViewById(R.id.albumTitle),
            row.findViewById(R.id.albumMeta),
            row.findViewById(R.id.albumCover)
        )
        row.setTag(R.id.album_row_views, created)
        return created
    }

    fun inflate(parent: ViewGroup): View =
        LayoutInflater.from(parent.context)
            .inflate(R.layout.item_album, parent, false)
            .also { applyTerminalTypeface(it) }

    fun bind(row: View, album: Album, onClick: () -> Unit) {
        val v = views(row)
        if (v.boundAlbum !== album) {
            v.boundAlbum = album
            v.title.text = album.title?.takeIf { it.isNotBlank() } ?: "Альбом"

            val meta = StringBuilder()
            album.year?.let { meta.append(it) }
            val artist = album.artistText()
            if (meta.isNotEmpty()) meta.append(" • ")
            meta.append(artist)
            v.meta.text = meta.toString()
        }

        val cover = v.cover
        val url = album.coverUrl(200)
        if (v.boundCoverUrl != url) {
            v.boundCoverUrl = url
            if (url.isNullOrBlank()) {
                Glide.with(row).clear(cover)
                cover.setPadding(
                    row.context.dp(14), row.context.dp(14),
                    row.context.dp(14), row.context.dp(14)
                )
                cover.scaleType = ImageView.ScaleType.FIT_CENTER
                cover.setImageDrawable(AppCompatResources.getDrawable(row.context, R.drawable.ic_album))
            } else {
                cover.setPadding(0, 0, 0, 0)
                cover.scaleType = ImageView.ScaleType.CENTER_CROP
                Glide.with(row)
                    .load(url)
                    .apply(coverOptions(row.context, 8))
                    .into(cover)
            }
        }

        row.setOnClickListener { onClick() }
    }
}

class AlbumAdapter(
    private val onClick: (Album) -> Unit
) : RecyclerView.Adapter<AlbumAdapter.Holder>() {

    private val items = ArrayList<Album>()

    fun submit(list: List<Album>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(AlbumRowBinder.inflate(parent))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val album = items.getOrNull(position) ?: return
        AlbumRowBinder.bind(holder.itemView, album) { onClick(album) }
    }

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView)
}

class PlaylistAdapter(
    private val onClick: (PlaylistSummary) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.Holder>() {

    private val items = ArrayList<PlaylistSummary>()

    fun submit(list: List<PlaylistSummary>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun items(): List<PlaylistSummary> = items

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
                .also { applyTerminalTypeface(it) }
        )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val playlist = items.getOrNull(position) ?: return
        val row = holder.itemView
        row.findViewById<TextView>(R.id.playlistTitle).text = playlist.title ?: "Плейлист"
        row.findViewById<TextView>(R.id.playlistMeta).text =
            "${playlist.ownerName()} • ${playlist.trackCount} треков"

        val cover = row.findViewById<ImageView>(R.id.playlistCover)
        val url = playlist.coverUrl(200)
        if (url.isNullOrBlank()) {
            Glide.with(row).clear(cover)
            // Иконка-заглушка лежит под ImageView в item_playlist.xml.
            cover.setImageDrawable(null)
        } else {
            Glide.with(row)
                .load(url)
                .apply(coverOptions(row.context, 8))
                .into(cover)
        }

        row.setOnClickListener { onClick(playlist) }
    }

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView)
}

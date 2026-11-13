package ru.yamus.app.ui.artist

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import ru.yamus.app.R
import ru.yamus.app.core.models.Album
import ru.yamus.app.core.models.Track
import ru.yamus.app.ui.common.AlbumRowBinder
import ru.yamus.app.ui.common.TrackRowBinder
import ru.yamus.app.ui.common.applySectionHeaderStyle
import ru.yamus.app.ui.common.applyTerminalTypeface
import ru.yamus.app.ui.common.avatarOptions
import ru.yamus.app.ui.common.setCircleBackground
import ru.yamus.app.ui.common.setSectionHeaderText

/**
 * Содержимое страницы исполнителя одним списком: шапка с аватаром,
 * блок «Все треки», популярные треки и альбомы.
 *
 * Раньше всё это лежало в ScrollView с LinearLayout, и на старых телефонах
 * при открытии артиста приходилось разом создавать десятки строк — экран
 * подвисал на секунды. RecyclerView создаёт только видимые строки и
 * переиспользует их при прокрутке.
 */
class ArtistPageAdapter(
    private val onPlayTrack: (Int) -> Unit,
    private val onOpenAlbum: (Album) -> Unit,
    private val onOpenAllTracks: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Item {
        class Header(
            val name: CharSequence,
            val avatarUrl: String?,
            val tracksCount: CharSequence
        ) : Item()

        class Section(val titleRes: Int) : Item()
        class TrackItem(val track: Track, val index: Int) : Item()
        class AlbumItem(val album: Album) : Item()
    }

    private val items = ArrayList<Item>()

    fun renderHeader(name: CharSequence, avatarUrl: String?, tracksCount: CharSequence) {
        if (items.isEmpty()) {
            items.add(Item.Header(name, avatarUrl, tracksCount))
            notifyDataSetChanged()
        } else {
            items[0] = Item.Header(name, avatarUrl, tracksCount)
            notifyItemChanged(0)
        }
    }

    fun renderContent(tracks: List<Track>, albums: List<Album>) {
        val header = items.firstOrNull { it is Item.Header } ?: Item.Header("", null, "")
        items.clear()
        items.add(header)
        if (tracks.isNotEmpty()) {
            items.add(Item.Section(R.string.popular_tracks))
            tracks.forEachIndexed { index, track ->
                items.add(Item.TrackItem(track, index))
            }
        }
        if (albums.isNotEmpty()) {
            items.add(Item.Section(R.string.albums))
            albums.forEach { items.add(Item.AlbumItem(it)) }
        }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is Item.Header -> TYPE_HEADER
        is Item.Section -> TYPE_SECTION
        is Item.TrackItem -> TYPE_TRACK
        is Item.AlbumItem -> TYPE_ALBUM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            TYPE_HEADER -> HeaderHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_artist_header, parent, false)
                    .also { applyTerminalTypeface(it) }
            ).also { holder ->
                holder.avatarCircle.setCircleBackground(parent.context, R.attr.yamusCard)
            }
            TYPE_SECTION -> SectionHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_section_title, parent, false)
            ).also { (it.itemView as TextView).applySectionHeaderStyle() }
            TYPE_TRACK -> TrackHolder(TrackRowBinder.inflate(parent))
            else -> AlbumHolder(AlbumRowBinder.inflate(parent))
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.Header -> (holder as HeaderHolder).bind(item)
            is Item.Section -> (holder as SectionHolder).bind(item.titleRes)
            is Item.TrackItem -> (holder as TrackHolder).bind(item)
            is Item.AlbumItem -> (holder as AlbumHolder).bind(item)
        }
    }

    private inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatarCircle: View = view.findViewById(R.id.avatarCircle)
        private val avatar: ImageView = view.findViewById(R.id.avatar)
        private val name: TextView = view.findViewById(R.id.artistName)
        private val count: TextView = view.findViewById(R.id.allTracksCount)

        fun bind(item: Item.Header) {
            name.text = item.name
            count.text = item.tracksCount

            val url = item.avatarUrl
            if (url.isNullOrBlank()) {
                Glide.with(itemView).clear(avatar)
                avatar.setImageDrawable(null)
            } else {
                Glide.with(itemView)
                    .load(url)
                    .apply(avatarOptions(itemView.context))
                    .into(avatar)
            }

            itemView.findViewById<View>(R.id.allTracksRow)
                .setOnClickListener { onOpenAllTracks() }
        }
    }

    private class SectionHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(titleRes: Int) {
            (itemView as TextView).setSectionHeaderText(titleRes)
        }
    }

    private inner class TrackHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(item: Item.TrackItem) {
            TrackRowBinder.bind(itemView, item.track) { onPlayTrack(item.index) }
        }
    }

    private inner class AlbumHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(item: Item.AlbumItem) {
            AlbumRowBinder.bind(itemView, item.album) { onOpenAlbum(item.album) }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_SECTION = 1
        const val TYPE_TRACK = 2
        const val TYPE_ALBUM = 3
    }
}

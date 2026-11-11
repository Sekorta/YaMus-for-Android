package ru.yamus.app.ui.album

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import ru.yamus.app.R
import ru.yamus.app.core.models.Track
import ru.yamus.app.ui.common.TrackRowBinder
import ru.yamus.app.ui.common.applySectionHeaderStyle

/**
 * Шапка альбома и его треки одним списком. Строки создаются по мере
 * прокрутки — так экран открывается быстро даже на старых телефонах.
 */
class AlbumPageAdapter(
    private val onPlayTrack: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Item {
        class Header(
            val coverUrl: String?,
            val title: CharSequence,
            val artists: CharSequence,
            val meta: CharSequence
        ) : Item()

        class Section(val titleRes: Int) : Item()
        class TrackItem(val track: Track, val index: Int) : Item()
    }

    private val items = ArrayList<Item>()

    fun renderHeader(
        coverUrl: String?,
        title: CharSequence,
        artists: CharSequence,
        meta: CharSequence
    ) {
        if (items.isEmpty()) {
            items.add(Item.Header(coverUrl, title, artists, meta))
            notifyDataSetChanged()
        } else {
            items[0] = Item.Header(coverUrl, title, artists, meta)
            notifyItemChanged(0)
        }
    }

    fun renderTracks(tracks: List<Track>) {
        val header = items.firstOrNull { it is Item.Header }
            ?: Item.Header(null, "", "", "")
        items.clear()
        items.add(header)
        if (tracks.isNotEmpty()) {
            items.add(Item.Section(R.string.search_tracks))
            tracks.forEachIndexed { index, track ->
                items.add(Item.TrackItem(track, index))
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is Item.Header -> TYPE_HEADER
        is Item.Section -> TYPE_SECTION
        is Item.TrackItem -> TYPE_TRACK
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            TYPE_HEADER -> HeaderHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_album_header, parent, false)
            )
            TYPE_SECTION -> SectionHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_section_title, parent, false)
            ).also { (it.itemView as TextView).applySectionHeaderStyle() }
            else -> TrackHolder(TrackRowBinder.inflate(parent))
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.Header -> (holder as HeaderHolder).bind(item)
            is Item.Section -> (holder as SectionHolder).bind(item.titleRes)
            is Item.TrackItem -> (holder as TrackHolder).bind(item)
        }
    }

    private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ImageView = view.findViewById(R.id.cover)
        private val title: TextView = view.findViewById(R.id.albumTitle)
        private val artists: TextView = view.findViewById(R.id.albumArtists)
        private val meta: TextView = view.findViewById(R.id.albumMeta)

        fun bind(item: Item.Header) {
            title.text = item.title
            artists.text = item.artists
            meta.text = item.meta

            val url = item.coverUrl
            if (url.isNullOrBlank()) {
                Glide.with(itemView).clear(cover)
                cover.setImageDrawable(null)
            } else {
                Glide.with(itemView).load(url).into(cover)
            }
        }
    }

    private class SectionHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(titleRes: Int) {
            (itemView as TextView).setText(titleRes)
        }
    }

    private inner class TrackHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(item: Item.TrackItem) {
            TrackRowBinder.bind(itemView, item.track) { onPlayTrack(item.index) }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_SECTION = 1
        const val TYPE_TRACK = 2
    }
}

package ru.yamus.app.ui.common

import android.app.Activity
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import kotlinx.coroutines.launch
import ru.yamus.app.R
import ru.yamus.app.core.data.LikesStore
import ru.yamus.app.core.player.PlaybackManager
import ru.yamus.app.core.util.AppScope
import ru.yamus.app.core.util.artistText
import ru.yamus.app.core.util.coverUrl
import ru.yamus.app.core.util.displayTitle
import ru.yamus.app.core.util.dp

/**
 * Мини-плеер и нижняя панель навигации. Одинаково работает на главной
 * и на вторичных экранах (альбом, артист, плейлист, «Мне нравится»).
 */
class ChromeController(
    private val activity: Activity,
    activeTab: Int,
    private val onTabSelected: (Int) -> Unit,
    private val onMiniClick: () -> Unit
) : PlaybackManager.Listener {

    private val miniPlayer: View? = activity.findViewById(R.id.miniPlayer)
    private val miniCover: ImageView? = activity.findViewById(R.id.miniCover)
    private val miniTitle: TextView? = activity.findViewById(R.id.miniTitle)
    private val miniArtist: TextView? = activity.findViewById(R.id.miniArtist)
    private val miniLike: IconView? = activity.findViewById(R.id.miniLike)
    private val miniPlayPause: IconView? = activity.findViewById(R.id.miniPlayPause)

    private val tabViews = arrayOfNulls<View>(3)
    private val tabIcons = arrayOfNulls<IconView>(3)
    private val tabTexts = arrayOfNulls<TextView>(3)

    private var activeTab = activeTab
    private var started = false
    private var lastTrackId: String? = null
    private var lastPlaying: Boolean? = null
    private var lastLiked: Boolean? = null

    private val likesListener: () -> Unit = { refresh() }

    init {
        tabViews[0] = activity.findViewById(R.id.tabHome)
        tabViews[1] = activity.findViewById(R.id.tabSearch)
        tabViews[2] = activity.findViewById(R.id.tabMe)
        tabIcons[0] = activity.findViewById(R.id.tabHomeIcon)
        tabIcons[1] = activity.findViewById(R.id.tabSearchIcon)
        tabIcons[2] = activity.findViewById(R.id.tabMeIcon)
        tabTexts[0] = activity.findViewById(R.id.tabHomeText)
        tabTexts[1] = activity.findViewById(R.id.tabSearchText)
        tabTexts[2] = activity.findViewById(R.id.tabMeText)

        miniPlayer?.setOnClickListener { onMiniClick() }
        miniPlayPause?.setOnClickListener { PlaybackManager.toggle() }
        miniLike?.setOnClickListener {
            val track = PlaybackManager.currentTrack ?: return@setOnClickListener
            AppScope.launch { LikesStore.toggle(track) }
        }
        for (i in tabViews.indices) {
            tabViews[i]?.setOnClickListener { onTabSelected(i) }
        }
        updateTabs()
    }

    fun setActiveTab(index: Int) {
        activeTab = index
        updateTabs()
    }

    fun start() {
        if (started) return
        started = true
        PlaybackManager.addListener(this)
        LikesStore.addListener(likesListener)
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        PlaybackManager.removeListener(this)
        LikesStore.removeListener(likesListener)
    }

    override fun onPlaybackChanged() {
        activity.runOnUiThread { refresh() }
    }

    private fun updateTabs() {
        val accent = ThemeManager.color(activity, R.attr.yamusAccent)
        val inactive = ThemeManager.color(activity, R.attr.yamusTextSub)
        for (i in tabViews.indices) {
            val color = if (i == activeTab) accent else inactive
            tabIcons[i]?.setTintColor(color)
            tabTexts[i]?.setTextColor(color)
        }
    }

    private fun refresh() {
        val player = miniPlayer ?: return
        val track = PlaybackManager.currentTrack
        if (track == null) {
            player.visibility = View.GONE
            lastTrackId = null
            lastPlaying = null
            lastLiked = null
            return
        }
        player.visibility = View.VISIBLE

        if (lastTrackId != track.id) {
            lastTrackId = track.id
            miniTitle?.text = track.displayTitle()
            miniArtist?.text = track.artistText()
            miniCover?.let {
                Glide.with(activity)
                    .load(track.coverUrl(200))
                    .apply(RequestOptions.bitmapTransform(RoundedCorners(activity.dp(6))))
                    .into(it)
            }
        }

        val playing = PlaybackManager.isPlaying
        if (lastPlaying != playing) {
            lastPlaying = playing
            miniPlayPause?.setImageResource(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play
            )
            miniPlayPause?.contentDescription = activity.getString(
                if (playing) R.string.pause else R.string.play
            )
        }

        val liked = LikesStore.isLiked(track.id)
        if (lastLiked != liked) {
            lastLiked = liked
            miniLike?.setImageResource(
                if (liked) R.drawable.ic_heart else R.drawable.ic_heart_outline
            )
            miniLike?.setTintColor(
                ThemeManager.color(
                    activity,
                    if (liked) R.attr.yamusAccent else R.attr.yamusTextSub
                )
            )
            miniLike?.contentDescription = activity.getString(
                if (liked) R.string.track_unlike else R.string.track_like
            )
        }
    }
}

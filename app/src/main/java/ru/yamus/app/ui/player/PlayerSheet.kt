package ru.yamus.app.ui.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import kotlinx.coroutines.launch
import ru.yamus.app.R
import ru.yamus.app.core.data.DislikesStore
import ru.yamus.app.core.data.LikesStore
import ru.yamus.app.core.player.PlaybackManager
import ru.yamus.app.core.util.AppScope
import ru.yamus.app.core.util.artistText
import ru.yamus.app.core.util.coverUrl
import ru.yamus.app.core.util.displayTitle
import ru.yamus.app.core.util.dp
import ru.yamus.app.core.util.formatDuration
import ru.yamus.app.core.util.mainAlbumId
import ru.yamus.app.ui.common.IconView
import ru.yamus.app.ui.common.Navigation
import ru.yamus.app.ui.common.ThemeManager
import ru.yamus.app.ui.common.TrackOptionsDialog
import ru.yamus.app.ui.common.setCircleBackground

/**
 * Полноэкранный плеер, который выезжает снизу поверх текущего экрана.
 * Один и тот же оверлей используется и на главной, и на вторичных страницах.
 */
class PlayerSheet(private val activity: Activity) : PlaybackManager.Listener {

    private val root: View
    private val panel: View
    private val scrim: View

    private val cover: ImageView
    private val title: TextView
    private val artist: TextView
    private val seekBar: SeekBar
    private val currentTime: TextView
    private val totalTime: TextView
    private val likeButton: IconView
    private val dislikeButton: IconView
    private val shuffleButton: IconView
    private val repeatButton: IconView
    private val playIcon: IconView

    private val handler = Handler(Looper.getMainLooper())

    private var isOpen = false
    private var attached = false
    private var userSeeking = false
    private var boundTrackId: String? = null
    private var lastPosition = -1
    private var lastDuration = -1

    private val ticker = object : Runnable {
        override fun run() {
            if (!isOpen) return
            updateProgress()
            handler.postDelayed(this, if (PlaybackManager.isPlaying) 500L else 1000L)
        }
    }

    private val likesListener: () -> Unit = { updateButtons() }
    private val dislikesListener: () -> Unit = { updateButtons() }

    init {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        root = LayoutInflater.from(activity)
            .inflate(R.layout.view_player_sheet, content, false)
        content.addView(root)

        panel = root.findViewById(R.id.playerSheetPanel)
        scrim = root.findViewById(R.id.playerSheetScrim)
        cover = root.findViewById(R.id.cover)
        title = root.findViewById(R.id.title)
        artist = root.findViewById(R.id.artist)
        seekBar = root.findViewById(R.id.seekBar)
        currentTime = root.findViewById(R.id.currentTime)
        totalTime = root.findViewById(R.id.totalTime)
        likeButton = root.findViewById(R.id.likeButton)
        dislikeButton = root.findViewById(R.id.dislikeButton)
        shuffleButton = root.findViewById(R.id.shuffleButton)
        repeatButton = root.findViewById(R.id.repeatButton)
        playIcon = root.findViewById(R.id.playIcon)

        root.findViewById<View>(R.id.backButton).setOnClickListener { close() }
        scrim.setOnClickListener { close() }

        val playButton = root.findViewById<View>(R.id.playButton)
        playButton.setCircleBackground(activity, R.attr.yamusAccent)
        playButton.setOnClickListener { PlaybackManager.toggle() }
        playIcon.setOnClickListener { PlaybackManager.toggle() }
        root.findViewById<View>(R.id.prevButton).setOnClickListener { PlaybackManager.previous() }
        root.findViewById<View>(R.id.nextButton).setOnClickListener { PlaybackManager.next() }
        shuffleButton.setOnClickListener { PlaybackManager.toggleShuffle() }
        repeatButton.setOnClickListener { PlaybackManager.toggleRepeat() }
        root.findViewById<View>(R.id.moreButton).setOnClickListener {
            PlaybackManager.currentTrack?.let { TrackOptionsDialog.show(activity, it) }
        }

        likeButton.setOnClickListener {
            val track = PlaybackManager.currentTrack ?: return@setOnClickListener
            AppScope.launch { LikesStore.toggle(track) }
        }
        dislikeButton.setOnClickListener {
            val track = PlaybackManager.currentTrack ?: return@setOnClickListener
            AppScope.launch { DislikesStore.toggle(track) }
        }

        title.setOnClickListener {
            val track = PlaybackManager.currentTrack ?: return@setOnClickListener
            close()
            Navigation.openAlbum(activity, track.mainAlbumId(), track.albums?.firstOrNull()?.title)
        }
        artist.setOnClickListener {
            val track = PlaybackManager.currentTrack ?: return@setOnClickListener
            close()
            Navigation.openArtistChooser(activity, track)
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) currentTime.text = formatDuration(progress.toLong())
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                userSeeking = false
                bar?.let { PlaybackManager.seekTo(it.progress) }
            }
        })
    }

    val isShowing: Boolean
        get() = isOpen

    fun attach() {
        if (attached) return
        attached = true
        PlaybackManager.addListener(this)
        LikesStore.addListener(likesListener)
        DislikesStore.addListener(dislikesListener)
        if (isOpen) {
            // Пока приложение было в фоне, трек мог смениться несколько раз:
            // слушатели в этот момент отключены, поэтому после возврата
            // перерисовываем содержимое плеера, а не ждём следующего события.
            refresh()
            startTicker()
        }
    }

    fun detach() {
        if (!attached) return
        attached = false
        PlaybackManager.removeListener(this)
        LikesStore.removeListener(likesListener)
        DislikesStore.removeListener(dislikesListener)
        stopTicker()
    }

    fun destroy() {
        detach()
        stopTicker()
        (root.parent as? ViewGroup)?.removeView(root)
    }

    fun open() {
        if (isOpen) return
        if (PlaybackManager.currentTrack == null) return
        isOpen = true
        boundTrackId = null
        bindTrack()
        updateButtons()
        updateProgress()
        root.visibility = View.VISIBLE
        scrim.alpha = 0f
        panel.post {
            if (!isOpen) return@post
            panel.translationY = panel.height.toFloat()
            panel.animate().translationY(0f).setDuration(260L).start()
            scrim.animate().alpha(1f).setDuration(260L).start()
        }
        startTicker()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        stopTicker()
        panel.animate()
            .translationY(panel.height.toFloat())
            .setDuration(220L)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isOpen) {
                        root.visibility = View.GONE
                        panel.translationY = 0f
                        panel.animate().setListener(null)
                    }
                }
            })
            .start()
        scrim.animate().alpha(0f).setDuration(220L).start()
    }

    /** Возвращает true, если нажатие «назад» обработал сам плеер. */
    fun handleBack(): Boolean {
        if (!isOpen) return false
        close()
        return true
    }

    override fun onPlaybackChanged() {
        activity.runOnUiThread { refresh() }
    }

    /** Синхронизирует открытый плеер с текущим состоянием [PlaybackManager]. */
    private fun refresh() {
        if (!isOpen) return
        if (PlaybackManager.currentTrack == null) {
            close()
            return
        }
        bindTrack()
        updateButtons()
        updateProgress()
    }

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun stopTicker() {
        handler.removeCallbacks(ticker)
    }

    private fun bindTrack() {
        val track = PlaybackManager.currentTrack
        if (track == null) {
            close()
            return
        }
        if (boundTrackId == track.id) return
        boundTrackId = track.id
        title.text = track.displayTitle()
        artist.text = track.artistText()
        Glide.with(activity)
            .load(track.coverUrl(400))
            .apply(RequestOptions.bitmapTransform(RoundedCorners(activity.dp(14))))
            .into(cover)
    }

    private fun updateButtons() {
        val track = PlaybackManager.currentTrack ?: return
        val accent = ThemeManager.color(activity, R.attr.yamusAccent)
        val inactive = ThemeManager.color(activity, R.attr.yamusTextSub)

        playIcon.setImageResource(
            if (PlaybackManager.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        playIcon.contentDescription = activity.getString(
            if (PlaybackManager.isPlaying) R.string.pause else R.string.play
        )

        val liked = LikesStore.isLiked(track.id)
        likeButton.setImageResource(
            if (liked) R.drawable.ic_heart else R.drawable.ic_heart_outline
        )
        likeButton.setTintColor(if (liked) accent else inactive)
        likeButton.contentDescription = activity.getString(
            if (liked) R.string.track_unlike else R.string.track_like
        )

        val disliked = DislikesStore.isDisliked(track.id)
        dislikeButton.setTintColor(if (disliked) accent else inactive)
        dislikeButton.contentDescription = activity.getString(
            if (disliked) R.string.track_undislike else R.string.track_dislike
        )

        val wave = PlaybackManager.waveActive
        shuffleButton.isEnabled = !wave
        shuffleButton.alpha = if (wave) 0.35f else 1f
        shuffleButton.setTintColor(if (PlaybackManager.shuffle && !wave) accent else inactive)
        shuffleButton.contentDescription = activity.getString(
            if (wave) R.string.shuffle_unavailable else R.string.shuffle
        )

        when (PlaybackManager.repeatMode) {
            PlaybackManager.REPEAT_ONE -> {
                repeatButton.setImageResource(R.drawable.ic_repeat_one)
                repeatButton.setTintColor(accent)
                repeatButton.contentDescription = activity.getString(R.string.repeat_one)
            }
            PlaybackManager.REPEAT_ALL -> {
                repeatButton.setImageResource(R.drawable.ic_repeat)
                repeatButton.setTintColor(accent)
                repeatButton.contentDescription = activity.getString(R.string.repeat_all)
            }
            else -> {
                repeatButton.setImageResource(R.drawable.ic_repeat)
                repeatButton.setTintColor(inactive)
                repeatButton.contentDescription = activity.getString(R.string.repeat_off)
            }
        }
    }

    private fun updateProgress() {
        val duration = PlaybackManager.duration()
        val position = PlaybackManager.position()
        if (!userSeeking) {
            val max = if (duration > 0) duration else 1
            if (seekBar.max != max) seekBar.max = max
            val target = position.coerceIn(0, seekBar.max)
            if (seekBar.progress != target) seekBar.progress = target
        }
        if (lastPosition != position) {
            lastPosition = position
            currentTime.text = formatDuration(position.toLong())
        }
        if (lastDuration != duration) {
            lastDuration = duration
            totalTime.text = formatDuration(duration.toLong())
        }
    }
}

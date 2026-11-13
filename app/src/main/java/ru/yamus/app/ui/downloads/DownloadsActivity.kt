package ru.yamus.app.ui.downloads

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import ru.yamus.app.R
import ru.yamus.app.core.auth.TokenStorage
import ru.yamus.app.core.download.LibraryStorage
import ru.yamus.app.core.download.OfflineEntry
import ru.yamus.app.core.download.OfflineLibrary
import ru.yamus.app.core.models.Track
import ru.yamus.app.core.player.PlaybackManager
import ru.yamus.app.core.util.CacheManager
import ru.yamus.app.ui.common.BaseActivity
import ru.yamus.app.ui.common.IconView
import ru.yamus.app.ui.common.MenuDialog
import ru.yamus.app.ui.common.StoragePermission
import ru.yamus.app.ui.common.TrackAdapter
import ru.yamus.app.ui.common.styleTerminalDialog
import ru.yamus.app.ui.main.MainActivity

/**
 * Список скачанных треков.
 *
 * Это обычная пользовательская папка, а не кэш: отсюда файлы можно играть
 * без сети, удалять по одному или все сразу. Формат (MP3/FLAC/M4A/AAC/OGG)
 * подписан у каждого трека.
 */
class DownloadsActivity : BaseActivity(), OfflineLibrary.Listener {

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, DownloadsActivity::class.java)
    }

    private lateinit var list: RecyclerView
    private lateinit var summary: TextView
    private lateinit var emptyPanel: View
    private lateinit var progress: ProgressBar
    private lateinit var permissionPanel: View
    private lateinit var playAll: IconView
    private lateinit var more: IconView
    private lateinit var sort: IconView

    private lateinit var adapter: TrackAdapter
    private var tracks: List<Track> = emptyList()
    private var entries: List<OfflineEntry> = emptyList()
    private var sortMode: String = TokenStorage.SORT_ARTIST

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        list = findViewById(R.id.downloadsList)
        summary = findViewById(R.id.summary)
        emptyPanel = findViewById(R.id.emptyPanel)
        progress = findViewById(R.id.progress)
        permissionPanel = findViewById(R.id.permissionPanel)
        playAll = findViewById(R.id.playAllButton)
        more = findViewById(R.id.moreButton)
        sort = findViewById(R.id.sortButton)
        sortMode = TokenStorage.downloadsSort

        findViewById<IconView>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.permissionButton).setOnClickListener {
            StoragePermission.ensureRead(this) { refresh(forceScan = true) }
        }

        installChrome(-1) { MainActivity.open(this, it) }

        adapter = TrackAdapter { _, position ->
            if (tracks.isNotEmpty()) {
                PlaybackManager.playQueue(tracks, position, tag = "downloads")
            }
        }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        playAll.setOnClickListener {
            if (tracks.isNotEmpty()) PlaybackManager.playQueue(tracks, 0, tag = "downloads")
        }
        more.setOnClickListener { showMenu() }
        sort.setOnClickListener { showSort() }

        refresh(forceScan = false)
    }

    override fun onStart() {
        super.onStart()
        OfflineLibrary.addListener(this)
    }

    override fun onStop() {
        OfflineLibrary.removeListener(this)
        super.onStop()
    }

    override fun onLibraryChanged() {
        runOnUiThread { render() }
    }

    private fun refresh(forceScan: Boolean) {
        if (forceScan && OfflineLibrary.needsReadPermission(this)) {
            permissionPanel.visibility = View.VISIBLE
            StoragePermission.ensureRead(this) { refresh(forceScan = true) }
            render()
            return
        }
        render()
        if (!forceScan) return
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                OfflineLibrary.refresh(this@DownloadsActivity)
            } catch (_: Exception) {
            }
            progress.visibility = View.GONE
            render()
        }
    }

    private fun render() {
        entries = sorted(OfflineLibrary.entries())
        tracks = entries.map { OfflineLibrary.trackFromEntry(it) }
        adapter.submit(tracks)

        val size = entries.sumOf { it.size }
        summary.text = getString(
            R.string.tracks_count,
            entries.size
        ) + " · " + CacheManager.formatSize(size)
        summary.append(" · " + LibraryStorage.rootLabel())

        val hasTracks = entries.isNotEmpty()
        emptyPanel.visibility = if (hasTracks) View.GONE else View.VISIBLE
        playAll.visibility = if (hasTracks) View.VISIBLE else View.GONE
        more.visibility = if (hasTracks) View.VISIBLE else View.GONE
        permissionPanel.visibility =
            if (!hasTracks && OfflineLibrary.needsReadPermission(this)) View.VISIBLE
            else View.GONE
    }

    private fun sorted(source: List<OfflineEntry>): List<OfflineEntry> = when (sortMode) {
        TokenStorage.SORT_TITLE -> source.sortedWith(
            compareBy<OfflineEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.artist }
        )
        TokenStorage.SORT_NEWEST -> source.sortedByDescending { it.addedAt }
        TokenStorage.SORT_SIZE -> source.sortedByDescending { it.size }
        TokenStorage.SORT_DURATION -> source.sortedByDescending { it.durationMs }
        else -> source
    }

    private fun showSort() {
        val values = arrayOf(
            TokenStorage.SORT_ARTIST,
            TokenStorage.SORT_TITLE,
            TokenStorage.SORT_NEWEST,
            TokenStorage.SORT_SIZE,
            TokenStorage.SORT_DURATION
        )
        val labels = arrayOf(
            getString(R.string.sort_artist),
            getString(R.string.sort_title),
            getString(R.string.sort_newest),
            getString(R.string.sort_size),
            getString(R.string.sort_duration)
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.sort)
            .setSingleChoiceItems(
                labels,
                values.indexOf(sortMode).coerceAtLeast(0)
            ) { d, which ->
                sortMode = values[which]
                TokenStorage.downloadsSort = sortMode
                d.dismiss()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        styleTerminalDialog(dialog)
    }

    private fun showMenu() {
        val groups = ArrayList<List<MenuDialog.Item>>()
        groups.add(
            listOf(
                MenuDialog.Item(getString(R.string.downloads_play_all)) {
                    if (tracks.isNotEmpty()) {
                        PlaybackManager.playQueue(tracks, 0, tag = "downloads")
                    }
                },
                MenuDialog.Item(getString(R.string.open_folder)) {
                    if (!LibraryStorage.openFolder(this)) {
                        PlaybackManager.toast(getString(R.string.folder_unavailable))
                    }
                }
            )
        )
        groups.add(
            listOf(
                MenuDialog.Item(getString(R.string.sort)) {
                    showSort()
                },
                MenuDialog.Item(getString(R.string.downloads_refresh)) {
                    refresh(forceScan = true)
                }
            )
        )
        groups.add(
            listOf(
                MenuDialog.Item(getString(R.string.downloads_clear_all)) {
                    confirmClearAll()
                }
            )
        )
        MenuDialog.show(this, getString(R.string.downloads_title), groups)
    }

    private fun confirmClearAll() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.downloads_clear_all)
            .setMessage(R.string.downloads_clear_confirm)
            .setPositiveButton(R.string.ok) { _, _ ->
                OfflineLibrary.clearAll(this)
                PlaybackManager.toast(getString(R.string.download_deleted))
                runOnUiThread { render() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        styleTerminalDialog(dialog)
    }
}

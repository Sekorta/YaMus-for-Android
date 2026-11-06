package ru.yamus.app.ui.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.yamus.app.R
import ru.yamus.app.core.api.ApiClient
import ru.yamus.app.core.models.SearchResponse
import ru.yamus.app.core.player.PlaybackManager
import ru.yamus.app.ui.common.AlbumAdapter
import ru.yamus.app.ui.common.ArtistAdapter
import ru.yamus.app.ui.common.IconView
import ru.yamus.app.ui.common.Navigation
import ru.yamus.app.ui.common.ThemeManager
import ru.yamus.app.ui.common.TrackAdapter

class SearchFragment : Fragment() {

    private lateinit var input: EditText
    private lateinit var searchButton: IconView
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var progress: ProgressBar
    private lateinit var tabs: Array<TextView>

    private var mode = 0
    private var lastQuery = ""
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            mode = savedInstanceState.getInt(KEY_MODE, 0)
            lastQuery = savedInstanceState.getString(KEY_QUERY).orEmpty()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_MODE, mode)
        outState.putString(KEY_QUERY, lastQuery)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_search, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        input = view.findViewById(R.id.searchInput)
        searchButton = view.findViewById(R.id.searchButton)
        list = view.findViewById(R.id.searchList)
        empty = view.findViewById(R.id.searchEmpty)
        progress = view.findViewById(R.id.searchProgress)
        tabs = arrayOf(
            view.findViewById(R.id.tabTracks),
            view.findViewById(R.id.tabArtists),
            view.findViewById(R.id.tabAlbums)
        )

        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = null

        searchButton.setOnClickListener { performSearch() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else {
                false
            }
        }

        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener {
                if (mode != index) {
                    mode = index
                    updateTabColors()
                    // Ищем по тому, что сейчас в поле: человек мог набрать
                    // новый запрос и сразу переключить вкладку.
                    if (input.text.isNotBlank()) performSearch()
                    else showEmpty(getString(R.string.search_start))
                }
            }
        }

        updateTabColors()
    }

    /**
     * После поворота экрана или смены темы EditText восстанавливает текст
     * запроса, но результаты поиска живут только в адаптере. Без повторного
     * запроса человек видел бы введённый текст и надпись «Введите запрос».
     */
    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        runSearchIfNeeded()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) runSearchIfNeeded()
    }

    private fun runSearchIfNeeded() {
        if (isHidden || list.adapter != null) return
        if (input.text.isNullOrBlank()) return
        performSearch()
    }

    private fun performSearch() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) {
            showEmpty(getString(R.string.search_start))
            return
        }
        lastQuery = text
        searchJob?.cancel()
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            showLoading(true)
            val response: SearchResponse? = withContext(Dispatchers.IO) {
                try {
                    ApiClient.api.search(text, typeForMode(mode), 0, 40)
                } catch (_: Exception) {
                    null
                }
            }
            if (!isAdded) return@launch
            showLoading(false)
            if (response?.result == null) {
                showEmpty(getString(R.string.error_network))
                return@launch
            }
            bind(response)
        }
    }

    private fun bind(response: SearchResponse) {
        val result = response.result ?: return
        when (mode) {
            0 -> {
                val tracks = result.tracks?.results.orEmpty()
                val adapter = TrackAdapter { _, index ->
                    if (tracks.isNotEmpty()) PlaybackManager.playQueue(tracks, index)
                }
                adapter.submit(tracks)
                list.adapter = adapter
                showEmptyIf(tracks.isEmpty())
            }
            1 -> {
                val artists = result.artists?.results.orEmpty()
                val adapter = ArtistAdapter { artist ->
                    Navigation.openArtist(requireContext(), artist.id, artist.name)
                }
                adapter.submit(artists)
                list.adapter = adapter
                showEmptyIf(artists.isEmpty())
            }
            else -> {
                val albums = result.albums?.results.orEmpty()
                val adapter = AlbumAdapter { album ->
                    Navigation.openAlbum(requireContext(), album.id, album.title)
                }
                adapter.submit(albums)
                list.adapter = adapter
                showEmptyIf(albums.isEmpty())
            }
        }
    }

    private fun showEmptyIf(isEmpty: Boolean) {
        empty.text = getString(R.string.search_empty)
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun showEmpty(message: String) {
        list.adapter = null
        empty.text = message
        empty.visibility = View.VISIBLE
        progress.visibility = View.GONE
    }

    private fun showLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) empty.visibility = View.GONE
    }

    private fun typeForMode(mode: Int): String = when (mode) {
        0 -> "track"
        1 -> "artist"
        else -> "album"
    }

    private fun updateTabColors() {
        val accent = ThemeManager.color(requireContext(), R.attr.yamusAccent)
        val inactive = ThemeManager.color(requireContext(), R.attr.yamusTextSub)
        tabs.forEachIndexed { index, tab ->
            tab.setTextColor(if (index == mode) accent else inactive)
        }
    }

    private companion object {
        const val KEY_MODE = "search_mode"
        const val KEY_QUERY = "search_query"
    }
}

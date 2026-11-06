package ru.yamus.app.ui.me

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.yamus.app.R
import ru.yamus.app.core.auth.TokenStorage
import ru.yamus.app.core.data.AccountStore
import ru.yamus.app.core.data.LikesStore
import ru.yamus.app.core.data.PlaylistRepository
import ru.yamus.app.core.util.coverUrl
import ru.yamus.app.ui.common.PlaylistAdapter
import ru.yamus.app.ui.common.SettingsDialog
import ru.yamus.app.ui.playlist.PlaylistActivity

class MeFragment : Fragment() {

    private lateinit var likedRow: View
    private lateinit var likedCount: TextView
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var progress: ProgressBar

    private lateinit var adapter: PlaylistAdapter
    private var loadJob: Job? = null

    private val likesListener: () -> Unit = { updateLikedCount() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_me, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        likedRow = view.findViewById(R.id.likedRow)
        likedCount = view.findViewById(R.id.likedCount)
        list = view.findViewById(R.id.playlistsList)
        empty = view.findViewById(R.id.meEmpty)
        progress = view.findViewById(R.id.meProgress)

        adapter = PlaylistAdapter { playlist ->
            val uid = AccountStore.uid
            if (uid > 0) {
                startActivity(
                    PlaylistActivity.intent(
                        requireContext(),
                        uid,
                        playlist.kind,
                        playlist.title,
                        playlist.coverUrl(400)
                    )
                )
            }
        }
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        likedRow.setOnClickListener {
            val uid = AccountStore.uid
            if (uid > 0) {
                startActivity(PlaylistActivity.likedIntent(requireContext(), uid))
            }
        }

        view.findViewById<View>(R.id.settingsButton).setOnClickListener {
            SettingsDialog.show(requireActivity())
        }

        updateLikedCount()
    }

    override fun onStart() {
        super.onStart()
        LikesStore.addListener(likesListener)
    }

    override fun onStop() {
        LikesStore.removeListener(likesListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateLikedCount()
        load()
    }

    private fun updateLikedCount() {
        if (!isAdded) return
        likedCount.text = getString(R.string.tracks_count, LikesStore.count())
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            empty.visibility = View.GONE

            val account = AccountStore.load()
            if (!isAdded) return@launch
            val uid = account?.uid ?: TokenStorage.uid
            if (uid <= 0) {
                progress.visibility = View.GONE
                adapter.submit(emptyList())
                empty.text = "Не удалось загрузить аккаунт"
                empty.visibility = View.VISIBLE
                return@launch
            }

            if (!LikesStore.loaded) LikesStore.load(uid)
            val playlists = PlaylistRepository.loadPlaylists(uid)
            if (!isAdded) return@launch

            adapter.submit(playlists)
            updateLikedCount()
            progress.visibility = View.GONE
            empty.text = getString(R.string.no_playlists)
            empty.visibility = if (playlists.isEmpty()) View.VISIBLE else View.GONE
        }
    }
}

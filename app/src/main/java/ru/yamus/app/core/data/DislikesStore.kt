package ru.yamus.app.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.yamus.app.core.api.ApiClient
import ru.yamus.app.core.models.Track
import java.util.concurrent.CopyOnWriteArrayList

/** Треки, отмеченные «Мне не нравится». */
object DislikesStore {
    private val ids = HashSet<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    var loaded: Boolean = false
        private set

    private val loadLock = Mutex()

    fun isDisliked(trackId: String?): Boolean =
        trackId != null && ids.contains(trackId)

    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        for (listener in listeners) {
            try {
                listener()
            } catch (_: Exception) {
            }
        }
    }

    suspend fun load(uid: Long) {
        if (uid <= 0) return
        if (loaded) return
        loadLock.withLock {
            if (loaded) return
            try {
                val response = withContext(Dispatchers.IO) {
                    ApiClient.api.getDislikedIds(uid)
                }
                val fresh = response.result?.library?.tracks?.mapNotNull { it.id }.orEmpty()
                synchronized(ids) {
                    ids.clear()
                    ids.addAll(fresh)
                }
                loaded = true
                notifyChanged()
            } catch (_: Exception) {
            }
        }
    }

    /** Возвращает новое состояние дизлайка. */
    suspend fun toggle(track: Track): Boolean {
        val id = track.id
        if (id.isBlank()) return false
        val uid = AccountStore.uid
        if (uid <= 0) return isDisliked(id)

        val wasDisliked = isDisliked(id)
        val ok = withContext(Dispatchers.IO) {
            try {
                if (wasDisliked) {
                    ApiClient.api.undislikeTrack(uid, id)
                } else {
                    ApiClient.api.dislikeTrack(uid, id)
                }
                true
            } catch (_: Exception) {
                false
            }
        }
        if (ok) {
            synchronized(ids) {
                if (wasDisliked) ids.remove(id) else ids.add(id)
            }
            // Сервер сам снимает лайк при добавлении дизлайка — обновляем
            // только локальное состояние.
            if (!wasDisliked) LikesStore.markRemoved(id)
            notifyChanged()
        }
        return isDisliked(id)
    }

    /** Локальное снятие дизлайка без запроса к API. */
    fun markRemoved(trackId: String?) {
        if (trackId == null) return
        val changed = synchronized(ids) { ids.remove(trackId) }
        if (changed) notifyChanged()
    }

    fun clear() {
        synchronized(ids) { ids.clear() }
        loaded = false
        notifyChanged()
    }
}

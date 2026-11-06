package ru.yamus.app.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.yamus.app.core.api.ApiClient
import ru.yamus.app.core.models.Track
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object LikesStore {
    private val ids = HashSet<String>()
    private val addedAt = HashMap<String, String>()
    private val serverOrder = HashMap<String, Int>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    var loaded: Boolean = false
        private set

    private val loadLock = Mutex()

    fun isLiked(trackId: String?): Boolean =
        trackId != null && ids.contains(trackId)

    fun count(): Int = synchronized(ids) { ids.size }

    /** Сначала недавно добавленные, затем более старые. */
    fun idsSnapshot(): List<String> = synchronized(ids) {
        ids.sortedWith(
            compareByDescending<String> { addedAt[it] ?: "" }
                .thenBy { serverOrder[it] ?: Int.MAX_VALUE }
        )
    }

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
        // Один общий запрос на всех: экран «Мне нравится» может открыться,
        // пока список ещё грузится на главной. Тогда он дождётся результата,
        // а не покажет пустой список.
        loadLock.withLock {
            if (loaded) return
            try {
                val response = withContext(Dispatchers.IO) {
                    ApiClient.api.getLikedIds(uid)
                }
                val items = response.result?.library?.tracks.orEmpty()
                synchronized(ids) {
                    ids.clear()
                    addedAt.clear()
                    serverOrder.clear()
                    items.forEachIndexed { index, item ->
                        val id = item.id ?: return@forEachIndexed
                        if (id.isBlank()) return@forEachIndexed
                        ids.add(id)
                        item.timestamp?.takeIf { it.isNotBlank() }?.let { addedAt[id] = it }
                        serverOrder[id] = index
                    }
                }
                loaded = true
                notifyChanged()
            } catch (_: Exception) {
            }
        }
    }

    /** Возвращает новое состояние лайка. */
    suspend fun toggle(track: Track): Boolean {
        val id = track.id
        if (id.isBlank()) return false
        val uid = AccountStore.uid
        if (uid <= 0) return isLiked(id)

        val wasLiked = isLiked(id)
        val ok = withContext(Dispatchers.IO) {
            try {
                if (wasLiked) {
                    ApiClient.api.unlikeTrack(uid, id)
                } else {
                    ApiClient.api.likeTrack(uid, id)
                }
                true
            } catch (_: Exception) {
                false
            }
        }
        if (ok) {
            synchronized(ids) {
                if (wasLiked) {
                    ids.remove(id)
                    addedAt.remove(id)
                    serverOrder.remove(id)
                } else {
                    ids.add(id)
                    addedAt[id] = nowTimestamp()
                    serverOrder[id] = -1
                }
            }
            // Сервер сам снимает дизлайк при добавлении лайка, поэтому
            // достаточно обновить локальное состояние без лишнего запроса:
            // повторный dislikes/remove у трека без дизлайка снимает лайк.
            if (!wasLiked) DislikesStore.markRemoved(id)
            notifyChanged()
        }
        return isLiked(id)
    }

    /** Локальное снятие лайка без запроса к API. */
    fun markRemoved(trackId: String?) {
        if (trackId == null) return
        val changed = synchronized(ids) {
            val removed = ids.remove(trackId)
            addedAt.remove(trackId)
            serverOrder.remove(trackId)
            removed
        }
        if (changed) notifyChanged()
    }

    fun clear() {
        synchronized(ids) {
            ids.clear()
            addedAt.clear()
            serverOrder.clear()
        }
        loaded = false
        notifyChanged()
    }

    private fun nowTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
}

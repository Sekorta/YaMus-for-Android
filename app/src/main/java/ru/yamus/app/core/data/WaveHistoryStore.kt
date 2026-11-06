package ru.yamus.app.core.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Треки, которые уже играли в «Моей волне».
 *
 * Станция при каждом новом запуске присылает одни и те же песни, если не
 * передать ей очередь прослушанного. Храним последние [MAX] id на диске,
 * чтобы волна не начинала со старых треков даже после перезапуска
 * приложения. История очищается при выходе из аккаунта и вручную.
 */
object WaveHistoryStore {

    private const val PREFS = "yamus_prefs"
    private const val KEY = "wave_history"
    private const val MAX = 150

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** Старые треки идут первыми, последние прослушанные — в конце. */
    @Synchronized
    fun ids(): List<String> {
        if (!::prefs.isInitialized) return emptyList()
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return raw.split(',').filter { it.isNotBlank() }
    }

    @Synchronized
    fun remember(trackId: String?) {
        if (trackId.isNullOrBlank() || !::prefs.isInitialized) return
        val ids = ids().toMutableList()
        // Повторно прослушанный трек переезжает в конец — так при переполнении
        // вытесняются действительно давние записи.
        ids.remove(trackId)
        ids.add(trackId)
        while (ids.size > MAX) ids.removeAt(0)
        prefs.edit().putString(KEY, ids.joinToString(",")).apply()
    }

    @Synchronized
    fun clear() {
        if (!::prefs.isInitialized) return
        prefs.edit().remove(KEY).apply()
    }
}

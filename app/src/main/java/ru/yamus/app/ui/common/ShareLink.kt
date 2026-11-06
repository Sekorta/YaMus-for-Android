package ru.yamus.app.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import ru.yamus.app.R
import ru.yamus.app.core.models.Track

/**
 * Ссылки на Яндекс Музыку для пункта «Поделиться».
 *
 * Копирование в буфер обмена работает на всех поддерживаемых версиях.
 * На Android 13+ система сама показывает всплывающую подсказку «Скопировано»,
 * поэтому свой Toast там не нужен.
 */
object ShareLink {

    private const val BASE = "https://music.yandex.ru"

    /**
     * У трека в API идентификатор бывает составным («12345:6789», где вторая
     * часть — альбом). Для ссылки нужна только первая часть.
     */
    fun track(track: Track): String? {
        val id = track.id.substringBefore(':').trim()
        if (id.isEmpty()) return null
        return "$BASE/track/$id"
    }

    fun album(albumId: Long): String? =
        if (albumId > 0) "$BASE/album/$albumId" else null

    fun artist(artistId: Long): String? =
        if (artistId > 0) "$BASE/artist/$artistId" else null

    fun copy(context: Context, url: String?) {
        if (url.isNullOrBlank()) {
            Toast.makeText(context, R.string.link_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            Toast.makeText(context, R.string.link_copy_failed, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            manager.setPrimaryClip(ClipData.newPlainText(url, url))
        } catch (_: Exception) {
            Toast.makeText(context, R.string.link_copy_failed, Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
        }
    }
}

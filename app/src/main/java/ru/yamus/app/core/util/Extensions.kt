package ru.yamus.app.core.util

import android.content.Context
import android.util.TypedValue
import ru.yamus.app.core.models.Album
import ru.yamus.app.core.models.Artist
import ru.yamus.app.core.models.PlaylistSummary
import ru.yamus.app.core.models.Track

fun Context.dp(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    // Без String.format: на Android 4.x он создаёт Formatter и парсит шаблон,
    // а вызывается это для каждой строки списка при каждой прокрутке.
    return if (seconds < 10) "$minutes:0$seconds" else "$minutes:$seconds"
}

fun String?.toCoverUrl(size: Int = 400): String? {
    if (this.isNullOrBlank()) return null
    val path = replace("%%", "${size}x$size")
    return if (path.startsWith("http://") || path.startsWith("https://")) path else "https://$path"
}

fun Track.displayTitle(): String {
    val base = title?.takeIf { it.isNotBlank() } ?: "Без названия"
    val v = version
    return if (!v.isNullOrBlank() && v != "original") "$base ($v)" else base
}

fun Track.artistText(): String = joinArtistNames(artists)

fun Track.mainAlbum(): Album? = albums?.firstOrNull()

fun Track.mainAlbumId(): Long = mainAlbum()?.id ?: 0L

fun Track.coverUrl(size: Int = 400): String? =
    coverUri.toCoverUrl(size) ?: mainAlbum()?.coverUri.toCoverUrl(size)

fun Track.isPlayable(): Boolean = id.isNotBlank() && available

fun Artist.imageUrl(size: Int = 400): String? =
    cover?.uri.toCoverUrl(size) ?: coverUri.toCoverUrl(size)

fun Album.artistText(): String = joinArtistNames(artists)

/**
 * Имена исполнителей одной строкой без промежуточных списков: функция
 * вызывается при каждой привязке строки списка, поэтому лишние аллокации
 * на старом Dalvik быстро приводят к паузам GC во время прокрутки.
 */
private fun joinArtistNames(artists: List<Artist>?): String {
    if (artists.isNullOrEmpty()) return "Неизвестный исполнитель"
    val builder = StringBuilder()
    for (artist in artists) {
        val name = artist.name
        if (name.isNullOrBlank()) continue
        if (builder.isNotEmpty()) builder.append(", ")
        builder.append(name)
    }
    return if (builder.isEmpty()) "Неизвестный исполнитель" else builder.toString()
}

fun Album.coverUrl(size: Int = 400): String? = coverUri.toCoverUrl(size)

fun PlaylistSummary.coverUrl(size: Int = 400): String? = cover?.uri.toCoverUrl(size)

fun PlaylistSummary.ownerName(): String =
    owner?.name?.takeIf { it.isNotBlank() }
        ?: owner?.login?.takeIf { it.isNotBlank() }
        ?: "Яндекс Музыка"

fun PlaylistSummary.isOwnedBy(uid: Long): Boolean =
    (owner?.uid ?: uid) == uid

fun Long?.orZero(): Long = this ?: 0L

fun ru.yamus.app.core.models.AccountInfo.displayName(): String =
    publicName?.takeIf { it.isNotBlank() }
        ?: login?.takeIf { it.isNotBlank() }
        ?: "Аккаунт"

fun ru.yamus.app.core.models.AccountInfo.avatarUrl(size: Int = 200): String? {
    avatarUri?.takeIf { it.isNotBlank() }?.let { return it.toCoverUrl(size) }
    avatar?.takeIf { it.isNotBlank() }?.let { return it.toCoverUrl(size) }
    avatarPortraitUri?.takeIf { it.isNotBlank() }?.let { return it.toCoverUrl(size) }
    avatarId?.takeIf { it.isNotBlank() }?.let {
        return "https://avatars.yandex.net/get-yapic/${it.trim('/')}/islands-$size"
    }
    return null
}

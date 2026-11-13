package ru.yamus.app.core.download

/**
 * Запись о скачанном треке.
 *
 * Хранится в приватном индексе для быстрого доступа, но не является
 * единственным источником правды: после переустановки индекс собирается
 * заново по самим файлам и их тегам.
 */
data class OfflineEntry(
    val trackId: String,
    val uri: String? = null,
    val path: String? = null,
    val displayName: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: Long = 0,
    val year: Int = 0,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val genre: String? = null,
    val durationMs: Long = 0,
    val format: String = "",
    val mime: String = "",
    val size: Long = 0,
    val addedAt: Long = 0L
)

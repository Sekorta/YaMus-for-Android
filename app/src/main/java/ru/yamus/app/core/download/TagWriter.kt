package ru.yamus.app.core.download

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.ArtworkFactory
import org.jaudiotagger.tag.reference.PictureTypes
import ru.yamus.app.core.models.Track
import ru.yamus.app.core.util.artistText
import ru.yamus.app.core.util.displayTitle
import ru.yamus.app.core.util.mainAlbum
import java.io.File

/**
 * Записывает в скачанный файл все метаданные, которые отдаёт Яндекс:
 * название, исполнителей, альбом, год, жанр, номер трека и диска, а также
 * обложку. Отдельных `cover.jpg` рядом с музыкой не остаётся — картинка
 * лежит внутри тега.
 *
 * MP3 (ID3v2), FLAC (Vorbis comments) и OGG пишет jaudiotagger. Для MP4/M4A
 * используется собственный [Mp4Tagger]: библиотечный `Mp4TagWriter`
 * пересобирает файл и на lossless `flac-mp4` ломает аудиопоток.
 *
 * Если формат не поддержан или запись не удалась, файл всё равно
 * сохраняется: музыка важнее тегов.
 */
object TagWriter {

    @Volatile
    private var configured = false

    data class Metadata(
        val title: String? = null,
        val artist: String? = null,
        val albumArtist: String? = null,
        val album: String? = null,
        val genre: String? = null,
        val year: String? = null,
        val track: Int = 0,
        val trackTotal: Int = 0,
        val disc: Int = 0,
        val artists: List<String> = emptyList(),
        val comment: String? = null
    )

    private fun configure() {
        if (configured) return
        synchronized(this) {
            if (configured) return
            try {
                TagOptionSingleton.getInstance().setAndroid(true)
                TagOptionSingleton.getInstance().setCheckIsWritable(false)
                TagOptionSingleton.getInstance().setWriteMp3GenresAsText(true)
                TagOptionSingleton.getInstance().setWriteMp4GenresAsText(true)
            } catch (_: Throwable) {
            }
            configured = true
        }
    }

    fun write(
        file: File,
        track: Track,
        cover: ByteArray?,
        coverMime: String?
    ): Boolean {
        if (!file.exists() || file.length() <= 0) return false
        configure()

        val meta = metadata(track)
        val ext = file.extension.lowercase()
        if (ext == "m4a" || ext == "mp4") {
            return Mp4Tagger.write(file, meta, cover, coverMime)
        }

        return try {
            val audio = AudioFileIO.read(file)
            val tag = audio.tag ?: audio.createDefaultTag().also { audio.tag = it }
            fill(tag, meta, cover, coverMime)
            AudioFileIO.write(audio)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun metadata(track: Track): Metadata {
        val album = track.mainAlbum()
        val artist = track.artistText()
        val albumArtists = album?.artists.orEmpty()
            .mapNotNull { it.name?.takeIf { name -> name.isNotBlank() } }
        val year = album?.year?.takeIf { it > 0 }
            ?: album?.releaseDate?.take(4)?.toIntOrNull()

        return Metadata(
            title = track.displayTitle(),
            artist = artist,
            albumArtist = albumArtists.joinToString(", ").ifBlank { artist },
            album = album?.title,
            genre = album?.genre,
            year = year?.toString(),
            track = album?.trackPosition?.index ?: 0,
            trackTotal = album?.trackCount ?: 0,
            disc = album?.trackPosition?.volume ?: 0,
            artists = track.artists.orEmpty()
                .mapNotNull { it.name?.takeIf { name -> name.isNotBlank() } },
            comment = sourceComment(track)
        )
    }

    private fun fill(
        tag: Tag,
        meta: Metadata,
        cover: ByteArray?,
        coverMime: String?
    ) {
        set(tag, FieldKey.TITLE, meta.title)
        set(tag, FieldKey.ARTIST, meta.artist)
        set(tag, FieldKey.ALBUM_ARTIST, meta.albumArtist)
        set(tag, FieldKey.ALBUM, meta.album)
        set(tag, FieldKey.GENRE, meta.genre)
        meta.year?.let { set(tag, FieldKey.YEAR, it) }
        meta.track.takeIf { it > 0 }?.let { set(tag, FieldKey.TRACK, it.toString()) }
        if (meta.trackTotal > 0) {
            set(tag, FieldKey.TRACK_TOTAL, meta.trackTotal.toString())
        }
        meta.disc.takeIf { it > 1 }?.let { set(tag, FieldKey.DISC_NO, it.toString()) }
        set(tag, FieldKey.COMMENT, meta.comment)

        // Многозначные теги: поддерживаются не всеми контейнерами,
        // поэтому ошибки здесь не должны ломать сохранение трека.
        if (meta.artists.size > 1) {
            try {
                tag.setField(FieldKey.ARTISTS, *meta.artists.toTypedArray())
            } catch (_: Throwable) {
            }
        }

        if (cover != null && cover.isNotEmpty()) {
            try {
                val artwork = ArtworkFactory.getNew()
                artwork.binaryData = cover
                artwork.mimeType = coverMime ?: "image/jpeg"
                artwork.description = "YaMus"
                artwork.pictureType = PictureTypes.DEFAULT_ID
                try {
                    artwork.setImageFromData()
                } catch (_: Throwable) {
                }
                tag.setField(artwork)
            } catch (_: Throwable) {
            }
        }
    }

    private fun set(tag: Tag, key: FieldKey, value: String?) {
        if (value.isNullOrBlank()) return
        try {
            tag.setField(key, value)
        } catch (_: Throwable) {
        }
    }

    private fun sourceComment(track: Track): String {
        val short = track.id.substringBefore(':')
        return "YaMus · https://music.yandex.ru/track/$short · id=${track.id}"
    }

    fun trackIdFromComment(comment: String?): String? {
        if (comment.isNullOrBlank()) return null
        val marker = comment.indexOf("id=")
        if (marker < 0) return null
        val value = comment.substring(marker + 3)
            .substringBefore(' ')
            .substringBefore('·')
            .trim()
        return value.takeIf { it.isNotBlank() }
    }
}

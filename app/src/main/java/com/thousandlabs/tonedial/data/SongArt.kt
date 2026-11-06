package com.thousandlabs.tonedial.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options

/** Pochette d'un morceau : image de l'album, sinon image intégrée au fichier. */
data class SongArt(val songId: Long, val albumId: Long)

fun Song.art() = SongArt(id, albumId)
fun Album.art() = SongArt(songs.firstOrNull()?.id ?: -1L, id)

class SongArtKeyer : Keyer<SongArt> {
    override fun key(data: SongArt, options: Options): String = "art_${data.albumId}_${data.songId}"
}

class SongArtFetcher(private val data: SongArt, private val options: Options) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val context = options.context
        val bytes: ByteArray? = runCatching {
            context.contentResolver.openInputStream(Song.albumArtUri(data.albumId))?.use { it.readBytes() }
        }.getOrNull() ?: if (data.songId >= 0) runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, Song.songUri(data.songId))
                r.embeddedPicture
            } finally {
                runCatching { r.release() }
            }
        }.getOrNull() else null

        if (bytes == null || bytes.isEmpty()) return null
        val bitmap = decode(bytes, 600) ?: return null
        return DrawableResult(
            drawable = BitmapDrawable(context.resources, bitmap),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    private fun decode(bytes: ByteArray, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    class Factory : Fetcher.Factory<SongArt> {
        override fun create(data: SongArt, options: Options, imageLoader: ImageLoader): Fetcher =
            SongArtFetcher(data, options)
    }
}

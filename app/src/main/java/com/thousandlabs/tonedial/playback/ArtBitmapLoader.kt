package com.thousandlabs.tonedial.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.thousandlabs.tonedial.R
import com.thousandlabs.tonedial.data.ArtBytes
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Images de la notification, du centre de contrôle et de l'écran verrouillé :
 * pochette d'album, sinon pochette intégrée au fichier, sinon le logo Tone Dial en grand.
 */
@OptIn(UnstableApi::class)
class ArtBitmapLoader(context: Context) : BitmapLoader {

    companion object {
        const val SCHEME = "tonedial"

        /** Adresse interne de la pochette d'un titre. */
        fun artUri(songId: Long, albumId: Long): Uri =
            Uri.Builder().scheme(SCHEME).authority("art")
                .appendQueryParameter("song", songId.toString())
                .appendQueryParameter("album", albumId.toString())
                .build()
    }

    private val app = context.applicationContext
    private val executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    @Volatile private var logo: Bitmap? = null

    override fun supportsMimeType(mimeType: String): Boolean = true

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        executor.submit(Callable { BitmapFactory.decodeByteArray(data, 0, data.size) ?: logoBitmap() })

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        executor.submit(Callable { load(uri) })

    private fun load(uri: Uri): Bitmap {
        val bytes: ByteArray? = if (uri.scheme == SCHEME) {
            val song = uri.getQueryParameter("song")?.toLongOrNull() ?: -1L
            val album = uri.getQueryParameter("album")?.toLongOrNull() ?: -1L
            ArtBytes.load(app, song, album)
        } else {
            runCatching { app.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        }
        return bytes?.let { ArtBytes.decode(it, 512) } ?: logoBitmap()
    }

    /** Logo Tone Dial (cadran doré sur fond sombre), dessiné une seule fois. */
    private fun logoBitmap(): Bitmap {
        logo?.let { return it }
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(ContextCompat.getColor(app, R.color.td_background))
        ContextCompat.getDrawable(app, R.drawable.ic_launcher_foreground)?.let { d ->
            // le dessin du logo occupe le centre de sa zone : on l'agrandit pour remplir l'image
            val pad = -size / 6
            d.setBounds(pad, pad, size - pad, size - pad)
            d.draw(canvas)
        }
        logo = bmp
        return bmp
    }
}

package com.thousandlabs.tonedial.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.TextPaint
import android.text.TextUtils

/** Image du bilan du mois, à partager (WhatsApp, statut…) et gardée dans la galerie. */
object RecapImage {

    data class Card(
        val month: String,
        val listening: String,
        val activeDays: Int,
        val streak: Int,
        val songs: Int,
        val topSong: String?,
        val topArtist: String?,
        val topOpening: String?,
        val top5: List<String>,
    )

    private const val W = 1080
    private const val H = 1350
    private val bg = Color.rgb(0x0D, 0x10, 0x17)
    private val surface = Color.rgb(0x17, 0x1B, 0x24)
    private val accent = Color.rgb(0xF2, 0xB5, 0x44)
    private val text = Color.rgb(0xED, 0xEF, 0xF4)
    private val dim = Color.rgb(0x9A, 0xA1, 0xB2)

    private fun paint(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun Canvas.line(s: String, x: Float, y: Float, p: TextPaint, maxWidth: Float) {
        drawText(TextUtils.ellipsize(s, p, maxWidth, TextUtils.TruncateAt.END).toString(), x, y, p)
    }

    fun render(card: Card): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(bg)
        // halo doré en haut, rappel du cadran de l'icône
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, 420f, Color.argb(70, 0xF2, 0xB5, 0x44), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, W.toFloat(), 420f, halo)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 10f; color = accent }
        c.drawCircle(W - 150f, 150f, 70f, ring)
        c.drawCircle(W - 150f, 150f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })

        val m = 80f
        val maxW = W - 2 * m
        c.line("TONE DIAL", m, 130f, paint(36f, accent, bold = true), maxW)
        c.line("Bilan de ${card.month.replaceFirstChar { it.lowercase() }}", m, 210f, paint(64f, text, bold = true), maxW - 160f)

        // chiffres clés
        val tileW = (maxW - 2 * 24f) / 3
        val stats = listOf(
            card.listening to "d'écoute",
            card.activeDays.toString() to if (card.activeDays <= 1) "jour d'écoute" else "jours d'écoute",
            card.streak.toString() to "jours d'affilée",
        )
        val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surface }
        stats.forEachIndexed { i, (value, label) ->
            val x = m + i * (tileW + 24f)
            c.drawRoundRect(RectF(x, 280f, x + tileW, 450f), 28f, 28f, box)
            c.line(value, x + 28f, 365f, paint(54f, accent, bold = true), tileW - 56f)
            c.line(label, x + 28f, 415f, paint(30f, dim), tileW - 56f)
        }

        var y = 520f
        fun highlight(label: String, value: String?) {
            if (value.isNullOrBlank()) return
            c.line(label.uppercase(), m, y, paint(30f, dim, bold = true), maxW)
            c.line(value, m, y + 58f, paint(46f, text, bold = true), maxW)
            y += 120f
        }
        highlight("Titre du mois", card.topSong)
        highlight("Artiste du mois", card.topArtist)
        highlight("Opening du mois", card.topOpening)

        if (card.top5.isNotEmpty()) {
            c.line("TOP 5", m, y, paint(30f, dim, bold = true), maxW)
            y += 52f
            card.top5.forEachIndexed { i, s ->
                c.drawText("${i + 1}", m, y, paint(40f, accent, bold = true))
                c.line(s, m + 60f, y, paint(38f, text), maxW - 60f)
                y += 54f
            }
        }

        c.line("${card.songs} titres différents écoutés", m, H - 55f, paint(30f, dim), maxW)
        return bmp
    }

    /** Enregistre l'image dans la galerie (Images > Tone Dial) et renvoie l'intention de partage. */
    fun saveAndShare(context: Context, card: Card): Intent? = runCatching {
        val bmp = render(card)
        val name = "ToneDial-bilan-" + card.month.lowercase().replace(' ', '-') + "-" + System.currentTimeMillis() / 1000 + ".png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Tone Dial")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        bmp.recycle()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(send, "Partager mon bilan").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }.getOrNull()
}

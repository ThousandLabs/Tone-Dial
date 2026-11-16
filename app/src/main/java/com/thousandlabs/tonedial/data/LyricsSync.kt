package com.thousandlabs.tonedial.data

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Recalage automatique des paroles téléchargées sur VOTRE fichier.
 *
 * Les paroles de LRCLIB sont calées sur une version précise de la chanson (souvent celle des
 * plateformes). Si votre fichier dure plus longtemps (clip avec intro, version longue) ou moins,
 * le défilement est décalé du début à la fin. On compare les deux durées, puis on écoute votre
 * fichier (volume au fil du temps) pour savoir où se trouve la différence :
 *  - au début (intro, silence, dialogue de clip) : les paroles sont retardées d'autant ;
 *  - à la fin (outro, silence final) : rien à décaler.
 * Quand le son ne permet pas de trancher, le minutage est marqué « à vérifier » et un bandeau
 * propose de le recaler d'un seul appui pendant l'écoute.
 */
object LyricsSync {

    /** Réglage : recaler automatiquement les paroles téléchargées. */
    fun enabled(): Boolean = Features.lyricsAutoSync.on

    // ---------- Minutage à vérifier (bandeau dans le lecteur) ----------

    fun isUnsure(songId: Long): Boolean = UserStore.getBool("lyrics_unsure_$songId", false)
    fun setUnsure(songId: Long, unsure: Boolean) {
        if (unsure) UserStore.putBool("lyrics_unsure_$songId", true)
        else if (UserStore.has("lyrics_unsure_$songId")) UserStore.putBool("lyrics_unsure_$songId", false)
    }

    /**
     * Recherche les paroles en ligne, les recale sur le fichier, les enregistre et note si le
     * minutage reste à vérifier. Renvoie le texte enregistré, ou null si rien n'a été trouvé.
     */
    suspend fun fetchAndSave(context: Context, song: Song, accept: (String) -> Boolean = { true }): String? {
        val found = LyricsOnline.findDetailed(song) ?: return null
        if (!accept(found.text)) return null
        val r = adjust(context, song, found.text, found.refDurationSec)
        UserStore.setCustomLyrics(song.id, r.text, source = "lrclib")
        setUnsure(song.id, r.unsure)
        return r.text
    }

    data class Adjusted(val text: String, val unsure: Boolean, val shiftMs: Long)

    /** Recale un texte .lrc sur le fichier. Un texte sans minutage est rendu tel quel. */
    suspend fun adjust(context: Context, song: Song, lrc: String, refDurationSec: Double): Adjusted {
        val lyrics = LyricsLoader.parse(lrc, "")
        if (!lyrics.synced || !enabled()) return Adjusted(lrc, false, 0)
        val localMs = song.duration
        val refMs = (refDurationSec * 1000).toLong()
        if (localMs <= 0 || refMs <= 0) return Adjusted(lrc, false, 0)
        // durées identiques à 1,5 s près : c'est la même version, rien à faire
        if (abs(localMs - refMs) <= SAME_VERSION_MS) return Adjusted(lrc, false, 0)
        val env = withContext(Dispatchers.Default) { runCatching { envelope(context, song.uri) }.getOrNull() }
        val d = decide(env, WINDOW_MS, localMs, refMs, lyrics.lines.firstOrNull()?.timeMs ?: 0L)
        val text = if (d.shiftMs != 0L) LyricsTiming.shift(lrc, 0, d.shiftMs) else lrc
        return Adjusted(text, d.unsure, d.shiftMs)
    }

    // ---------- Décision (sans Android, testable) ----------

    data class Decision(val shiftMs: Long, val unsure: Boolean)

    const val WINDOW_MS = 100
    private const val SAME_VERSION_MS = 1500L
    private const val MARGIN_MS = 2500L

    /**
     * `env` : volume en dB par tranche de [windowMs] millisecondes (null si le fichier n'a pas pu être lu).
     * `delta` = durée de votre fichier − durée de la version des paroles.
     */
    fun decide(env: FloatArray?, windowMs: Int, localMs: Long, refMs: Long, firstLyricMs: Long): Decision {
        val delta = localMs - refMs
        if (abs(delta) <= SAME_VERSION_MS) return Decision(0, false)
        if (env == null || env.size < 50) return Decision(0, true)
        val p = profile(env, windowMs, localMs)
        if (delta > 0) {
            // votre fichier est plus long : la différence est au début, à la fin, ou les deux
            val atStart = p.startQuietMs >= delta - MARGIN_MS
            val atEnd = p.endQuietMs >= delta - MARGIN_MS
            return when {
                atStart && !atEnd -> Decision(delta, false)
                atEnd && !atStart -> Decision(0, false)
                atStart && atEnd -> {
                    // les deux sont possibles : on prend le côté le plus « vide », à vérifier
                    if (p.startQuietMs - p.refIntroGuessMs >= p.endQuietMs) Decision(delta, true) else Decision(0, true)
                }
                // intro ET outro qui, ensemble, expliquent l'écart : décalage de la durée de l'intro
                abs(p.startQuietMs + p.endQuietMs - delta) <= 3000 -> Decision(min(p.startQuietMs, delta), true)
                // sinon, seul un vrai silence au début est sûr
                p.leadSilenceMs >= 1000 -> Decision(min(p.leadSilenceMs, delta), true)
                else -> Decision(0, true)
            }
        }
        // votre fichier est plus court (intro coupée, version radio) : la version des paroles
        // a du contenu en plus, au début ou à la fin
        val cut = -delta
        val firstAfterCut = firstLyricMs - cut
        return when {
            // la musique démarre tout de suite chez vous alors que les paroles attendent plus longtemps
            // que la coupure : l'intro a sans doute été raccourcie
            p.startQuietMs < 1500 && firstAfterCut >= 0 && firstLyricMs > cut + 1000 -> Decision(-cut, true)
            else -> Decision(0, true)
        }
    }

    data class Profile(val leadSilenceMs: Long, val startQuietMs: Long, val endQuietMs: Long, val refIntroGuessMs: Long)

    /**
     * Repères du fichier :
     *  - silence au début ;
     *  - début du « corps » du morceau : moment où le volume moyen sur 3 s atteint le niveau habituel du titre ;
     *  - fin du corps (ce qui suit est une outro calme ou du silence).
     */
    fun profile(env: FloatArray, windowMs: Int, localMs: Long): Profile {
        val n = env.size
        val audible = env.filter { it > -60f }.sorted()
        val median = if (audible.isEmpty()) -20f else audible[audible.size / 2]
        val level = median - 8f
        val span = max(1, 3000 / windowMs)
        // moyenne en puissance sur une fenêtre de 3 s
        val power = DoubleArray(n) { Math.pow(10.0, env[it] / 10.0) }
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + power[i]
        fun avgDb(from: Int, to: Int): Float {
            val a = from.coerceIn(0, n)
            val b = to.coerceIn(0, n)
            if (b <= a) return -100f
            return (10 * log10((prefix[b] - prefix[a]) / (b - a) + 1e-12)).toFloat()
        }
        // fenêtre courte (1 s) pour la précision, confirmée sur 3 s pour ignorer un bruit isolé
        val short = max(1, 1000 / windowMs)
        val startIdx = (0 until n).firstOrNull { avgDb(it, it + short) >= level && avgDb(it, it + span) >= level } ?: 0
        val endIdx = (n - 1 downTo 0).firstOrNull { avgDb(it - short + 1, it + 1) >= level && avgDb(it - span + 1, it + 1) >= level } ?: (n - 1)
        val leadIdx = (0 until n).firstOrNull { env[it] > max(-60f, median - 30f) } ?: 0
        val endMs = min(localMs, (endIdx + 1).toLong() * windowMs)
        return Profile(
            leadSilenceMs = leadIdx.toLong() * windowMs,
            startQuietMs = startIdx.toLong() * windowMs,
            endQuietMs = max(0L, localMs - endMs),
            // une intro calme d'une ou deux secondes existe aussi dans la version d'origine
            refIntroGuessMs = 1500L,
        )
    }

    // ---------- Lecture du son ----------

    /** Volume (dB) par tranche de 100 ms, en décodant le fichier sur le téléphone. Abandonne après 25 s. */
    fun envelope(context: Context, uri: Uri): FloatArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()

            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var floatPcm = false
            var perWindow = rate.toLong() * channels * WINDOW_MS / 1000
            val out = ArrayList<Float>(4000)
            var acc = 0.0
            var count = 0L
            val info = MediaCodec.BufferInfo()
            var inDone = false
            val deadline = System.currentTimeMillis() + 25_000

            fun add(v: Double) {
                acc += v * v
                count++
                if (count >= perWindow) {
                    out += (10 * log10(acc / count + 1e-12)).toFloat()
                    acc = 0.0
                    count = 0
                }
            }

            while (true) {
                if (System.currentTimeMillis() > deadline) return null
                if (!inDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            c.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    perWindow = rate.toLong() * channels * WINDOW_MS / 1000
                } else if (o >= 0) {
                    val b = c.getOutputBuffer(o)
                    if (b != null && info.size > 0) {
                        b.position(info.offset)
                        b.limit(info.offset + info.size)
                        val ordered = b.slice().order(ByteOrder.LITTLE_ENDIAN)
                        if (floatPcm) {
                            val fb = ordered.asFloatBuffer()
                            while (fb.hasRemaining()) add(fb.get().toDouble())
                        } else {
                            val sb = ordered.asShortBuffer()
                            while (sb.hasRemaining()) add(sb.get() / 32768.0)
                        }
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return out.toFloatArray()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }
}

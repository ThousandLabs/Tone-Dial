package com.thousandlabs.tonedial.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset

data class LyricLine(val timeMs: Long?, val text: String)

data class Lyrics(val lines: List<LyricLine>, val source: String) {
    val synced: Boolean get() = lines.isNotEmpty() && lines.all { it.timeMs != null }
    val plainText: String get() = lines.joinToString("\n") { it.text }
}

/**
 * Recherche les paroles d'un morceau, entièrement hors ligne :
 * 1. paroles saisies dans l'appli,
 * 2. fichier .lrc portant le même nom que la musique,
 * 3. paroles intégrées au fichier (MP3 / ID3, FLAC).
 */
object LyricsLoader {

    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val metaTag = Regex("""^\[(ar|ti|al|by|offset|length|re|ve|au|#):.*]$""", RegexOption.IGNORE_CASE)

    suspend fun load(context: Context, song: Song): Lyrics? = withContext(Dispatchers.IO) {
        UserStore.customLyrics(song.id)?.let {
            val src = if (UserStore.customLyricsSource(song.id) == "lrclib") "Téléchargées depuis LRCLIB" else "Saisies dans l'appli"
            return@withContext parse(it, src)
        }

        runCatching {
            val base = song.path.substringBeforeLast('.', song.path)
            listOf("$base.lrc", "$base.LRC", "$base.txt").map(::File).firstOrNull { it.canRead() }
                ?.readText()?.let { parse(it, "Fichier .lrc") }
        }.getOrNull()?.let { if (it.lines.isNotEmpty()) return@withContext it }

        runCatching {
            context.contentResolver.openInputStream(song.uri)?.use { input ->
                val lower = song.path.lowercase()
                val text = if (lower.endsWith(".flac")) readFlacLyrics(input) else readId3Lyrics(input)
                text?.let { parse(it, "Intégrées au fichier") }
            }
        }.getOrNull()?.takeIf { it.lines.isNotEmpty() }
    }

    fun parse(raw: String, source: String): Lyrics {
        val lines = ArrayList<LyricLine>()
        var anyTimed = false
        raw.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
            val trimmed = line.trim()
            if (metaTag.matches(trimmed)) return@forEach
            val matches = timeTag.findAll(trimmed).toList()
            if (matches.isNotEmpty() && trimmed.startsWith("[")) {
                anyTimed = true
                val text = trimmed.substring(matches.last().range.last + 1).trim()
                matches.forEach { m ->
                    val min = m.groupValues[1].toLong()
                    val sec = m.groupValues[2].toLong()
                    val fracRaw = m.groupValues[3]
                    val frac = when (fracRaw.length) {
                        0 -> 0L
                        1 -> fracRaw.toLong() * 100
                        2 -> fracRaw.toLong() * 10
                        else -> fracRaw.take(3).toLong()
                    }
                    lines += LyricLine(min * 60_000 + sec * 1000 + frac, text)
                }
            } else {
                lines += LyricLine(null, trimmed)
            }
        }
        val result = if (anyTimed) {
            lines.filter { it.timeMs != null }.sortedBy { it.timeMs }
        } else {
            lines.dropWhile { it.text.isBlank() }.dropLastWhile { it.text.isBlank() }
        }
        return Lyrics(result, source)
    }

    // ---------------- ID3v2 (MP3) ----------------
    private fun readId3Lyrics(input: InputStream): String? {
        val data = DataInputStream(input.buffered())
        val header = ByteArray(10)
        data.readFully(header)
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return null
        val version = header[3].toInt()
        val flags = header[5].toInt()
        val size = syncSafe(header, 6)
        if (size <= 0 || size > 32 * 1024 * 1024) return null
        var tag = ByteArray(size)
        data.readFully(tag)
        if (flags and 0x80 != 0 && version < 4) tag = removeUnsync(tag)

        var pos = 0
        if (flags and 0x40 != 0 && version >= 3) {
            val extSize = if (version == 4) syncSafe(tag, 0) else readInt(tag, 0) + 4
            pos += extSize
        }
        val idLen = if (version == 2) 3 else 4
        val headLen = if (version == 2) 6 else 10
        while (pos + headLen <= tag.size) {
            val id = String(tag, pos, idLen, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val frameSize = when (version) {
                2 -> ((tag[pos + 3].toInt() and 0xFF) shl 16) or ((tag[pos + 4].toInt() and 0xFF) shl 8) or (tag[pos + 5].toInt() and 0xFF)
                4 -> syncSafe(tag, pos + 4)
                else -> readInt(tag, pos + 4)
            }
            val start = pos + headLen
            if (frameSize <= 0 || start + frameSize > tag.size) break
            if (id == "USLT" || id == "ULT") {
                return decodeUslt(tag.copyOfRange(start, start + frameSize))
            }
            pos = start + frameSize
        }
        return null
    }

    private fun decodeUslt(frame: ByteArray): String? {
        if (frame.size < 5) return null
        val enc = frame[0].toInt()
        var i = 4 // encodage + langue (3 octets)
        val wide = enc == 1 || enc == 2
        // ignorer le descripteur de contenu terminé par un zéro
        if (wide) {
            while (i + 1 < frame.size && !(frame[i].toInt() == 0 && frame[i + 1].toInt() == 0)) i += 2
            i += 2
        } else {
            while (i < frame.size && frame[i].toInt() != 0) i++
            i += 1
        }
        if (i >= frame.size) return null
        val charset: Charset = when (enc) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(frame, i, frame.size - i, charset).trim('\u0000', ' ', '\n').ifBlank { null }
    }

    private fun syncSafe(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun readInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun removeUnsync(src: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(src.size)
        var i = 0
        while (i < src.size) {
            out.write(src[i].toInt())
            if ((src[i].toInt() and 0xFF) == 0xFF && i + 1 < src.size && src[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    // ---------------- FLAC (Vorbis comments) ----------------
    private fun readFlacLyrics(input: InputStream): String? {
        val data = DataInputStream(input.buffered())
        val magic = ByteArray(4)
        data.readFully(magic)
        if (String(magic, Charsets.ISO_8859_1) != "fLaC") return null
        while (true) {
            val h = data.readUnsignedByte()
            val last = h and 0x80 != 0
            val type = h and 0x7F
            val len = (data.readUnsignedByte() shl 16) or (data.readUnsignedByte() shl 8) or data.readUnsignedByte()
            if (type == 4) {
                val block = ByteArray(len)
                data.readFully(block)
                var p = 0
                fun le(): Int {
                    val v = (block[p].toInt() and 0xFF) or ((block[p + 1].toInt() and 0xFF) shl 8) or
                        ((block[p + 2].toInt() and 0xFF) shl 16) or ((block[p + 3].toInt() and 0xFF) shl 24)
                    p += 4
                    return v
                }
                val vendorLen = le()
                p += vendorLen
                val count = le()
                repeat(count) {
                    if (p + 4 > block.size) return null
                    val l = le()
                    if (p + l > block.size) return null
                    val entry = String(block, p, l, Charsets.UTF_8)
                    p += l
                    val key = entry.substringBefore('=').uppercase()
                    if (key == "LYRICS" || key == "UNSYNCEDLYRICS") return entry.substringAfter('=')
                }
                return null
            } else {
                var skip = len.toLong()
                while (skip > 0) {
                    val s = data.skip(skip)
                    if (s <= 0) return null
                    skip -= s
                }
            }
            if (last) return null
        }
    }
}

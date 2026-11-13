package com.thousandlabs.tonedial.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Traduction des paroles en français (Google Traduction, sans compte).
 * Seul le texte des paroles est envoyé. La traduction est gardée sur le téléphone :
 * la fois suivante, elle s'affiche sans connexion.
 */
object LyricsTranslate {

    sealed interface Outcome {
        data class Done(val lines: List<String>) : Outcome
        data object AlreadyFrench : Outcome
        data object Failed : Outcome
    }

    /** Afficher la traduction sous les paroles (bouton dans « Options »). */
    fun showEnabled(): Boolean = UserStore.getBool("lyrics_tr_show", true)
    fun setShow(on: Boolean) = UserStore.putBool("lyrics_tr_show", on)

    private fun key(songId: Long) = "lyrics_tr_$songId"

    /** Traduction déjà enregistrée pour ces paroles (null si les paroles ont changé depuis). */
    fun cached(songId: Long, lines: List<String>): List<String>? = runCatching {
        val o = JSONObject(UserStore.getString(key(songId)) ?: return null)
        if (o.optInt("h") != lines.joinToString("\n").hashCode()) return null
        val arr = o.getJSONArray("t")
        (0 until arr.length()).map { arr.getString(it) }.takeIf { it.size == lines.size }
    }.getOrNull()

    fun clear(songId: Long) = UserStore.putString(key(songId), null)

    suspend fun translate(songId: Long, lines: List<String>): Outcome = withContext(Dispatchers.IO) {
        cached(songId, lines)?.let { return@withContext Outcome.Done(it) }
        val idx = lines.indices.filter { lines[it].isNotBlank() }
        if (idx.isEmpty()) return@withContext Outcome.Failed
        val source = idx.map { lines[it].trim() }

        // tout d'un coup (une ligne par ligne) ; si le découpage ne correspond pas, ligne par ligne
        val whole = request(source.joinToString("\n")) ?: return@withContext Outcome.Failed
        if (whole.second == "fr") return@withContext Outcome.AlreadyFrench
        var out = whole.first.split('\n').map { it.trim() }
        if (out.size != source.size) {
            out = source.map { line -> request(line)?.first?.replace('\n', ' ')?.trim() ?: return@withContext Outcome.Failed }
        }
        val result = MutableList(lines.size) { "" }
        idx.forEachIndexed { k, i -> result[i] = out[k] }
        UserStore.putString(
            key(songId),
            JSONObject().put("h", lines.joinToString("\n").hashCode()).put("t", JSONArray(result)).toString(),
        )
        Outcome.Done(result)
    }

    /** Texte traduit et langue d'origine détectée. */
    private fun request(text: String): Pair<String, String>? = runCatching {
        val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=fr&dt=t")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("User-Agent", "ToneDial (https://github.com/ThousandLabs/Tone-Dial)")
        }
        try {
            // texte envoyé dans le corps de la requête : pas de limite de longueur d'adresse
            conn.outputStream.use { it.write(("q=" + URLEncoder.encode(text, "UTF-8")).toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return@runCatching null
            val root = JSONArray(conn.inputStream.bufferedReader().readText())
            val parts = root.getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.optJSONArray(i) ?: continue
                if (!p.isNull(0)) sb.append(p.optString(0))
            }
            val lang = root.optString(2, "")
            sb.toString() to lang
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}

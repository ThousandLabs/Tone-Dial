package com.thousandlabs.tonedial.licence

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Base64
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.UUID

/**
 * Licence Tone Dial : même système que les autres applis Thousand Labs.
 *
 * • Chaque téléphone a un « code appareil » TD-XXXX-XXXX, calculé à partir de
 *   l'identifiant Android du téléphone : il reste le même après une mise à jour,
 *   et même après une désinstallation suivie d'une réinstallation.
 * • Le client envoie ce code ; Thousand Admin le signe avec la clé privée de
 *   Tone Dial (qui ne quitte jamais le téléphone du vendeur).
 * • L'appli ne contient que la clé PUBLIQUE : elle vérifie la signature hors ligne.
 *   Une fois le code accepté, il est gardé : plus besoin de le saisir, jamais.
 *   Après une réinstallation, le même code fonctionne à nouveau sur ce téléphone.
 * • Essai gratuit de 48 h, une fois par téléphone ; heure du téléphone reculée
 *   de plus de 10 minutes pendant l'essai = essai terminé.
 */
object Licence {

    /** Clé publique Tone Dial (générée dans Thousand Admin, onglet Tone Dial, le 8 nov. 2026). */
    private const val PUB_X = "YcMriH32SZWIpFRRsA2AeuSD9IRVF6G6TTd7rGPECyE"
    private const val PUB_Y = "VyK9a5CTwUJ_M-9-p1d139cCmpPWqA4lCezIp352Nt4"

    const val PRICE = "1 000 F"
    const val WAVE_URL = "https://pay.wave.com/m/M_sn_2435mflS4o9D/c/sn/"
    const val WHATSAPP = "221711216581"
    const val PHONE_FMT = "71 121 65 81"
    const val TRIAL_MS = 48L * 3600 * 1000
    private const val ALPHA = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    enum class State { ACTIVE, TRIAL, NEW, EXPIRED }

    private fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("td_licence", Context.MODE_PRIVATE)

    val keyInstalled: Boolean get() = PUB_X.isNotEmpty() && PUB_Y.isNotEmpty()

    @Volatile private var cachedOk: Boolean? = null

    /** Code appareil TD-XXXX-XXXX (stable pour ce téléphone et cette appli). */
    @SuppressLint("HardwareIds")
    fun deviceId(c: Context): String {
        var base = runCatching { Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull()
        if (base.isNullOrBlank() || base == "9774d56d682e549c") {
            // identifiant Android absent (très rare) : identifiant aléatoire gardé sur le téléphone
            val p = prefs(c)
            base = p.getString("rnd", null) ?: UUID.randomUUID().toString().also { p.edit().putString("rnd", it).apply() }
        }
        val h = MessageDigest.getInstance("SHA-256").digest("tone-dial/$base".toByteArray())
        val s = StringBuilder()
        for (i in 0 until 8) s.append(ALPHA[h[i].toInt() and 31])
        return "TD-" + s.substring(0, 4) + "-" + s.substring(4)
    }

    /** Nettoie un code collé (espaces, retours à la ligne, préfixe « TDCODE: »). */
    fun clean(code: String): String =
        code.replace(Regex("\\s+"), "").replace(Regex("^TDCODE:", RegexOption.IGNORE_CASE), "")

    private fun b64u(s: String): ByteArray =
        Base64.decode(s.replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }, Base64.DEFAULT)

    /** Signature brute r||s (format du navigateur) → format DER attendu par Android. */
    private fun toDer(raw: ByteArray): ByteArray {
        if (raw.size != 64) return raw
        fun int(b: ByteArray): ByteArray {
            var i = 0
            while (i < b.size - 1 && b[i].toInt() == 0) i++
            var v = b.copyOfRange(i, b.size)
            if ((v[0].toInt() and 0x80) != 0) v = byteArrayOf(0) + v
            return byteArrayOf(0x02, v.size.toByte()) + v
        }
        val body = int(raw.copyOfRange(0, 32)) + int(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    /** Vérifie un code d'activation pour ce téléphone (hors ligne). */
    fun verify(c: Context, code: String): Boolean {
        if (!keyInstalled) return false
        return runCatching {
            val spec = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(ECParameterSpec::class.java)
            val pub = KeyFactory.getInstance("EC").generatePublic(
                ECPublicKeySpec(ECPoint(BigInteger(1, b64u(PUB_X)), BigInteger(1, b64u(PUB_Y))), spec)
            )
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(pub)
                update(deviceId(c).toByteArray(Charsets.UTF_8))
                verify(toDer(b64u(clean(code))))
            }
        }.getOrDefault(false)
    }

    fun isActivated(c: Context): Boolean {
        cachedOk?.let { return it }
        val code = prefs(c).getString("code", null)
        val ok = code != null && verify(c, code)
        cachedOk = ok
        return ok
    }

    /** Enregistre le code s'il est valide. Renvoie true si Tone Dial est activée. */
    fun activate(c: Context, code: String): Boolean {
        val cl = clean(code)
        if (cl.isEmpty() || !verify(c, cl)) return false
        prefs(c).edit().putString("code", cl).putLong("at", System.currentTimeMillis()).apply()
        cachedOk = true
        return true
    }

    fun activatedAt(c: Context): Long = prefs(c).getLong("at", 0L)

    fun startTrial(c: Context) {
        val p = prefs(c)
        if (p.getLong("trial", 0L) > 0L) return
        val now = System.currentTimeMillis()
        p.edit().putLong("trial", now).putLong("seen", now).apply()
    }

    /** Temps d'essai restant (0 si pas commencé ou terminé). */
    fun trialLeft(c: Context): Long {
        val p = prefs(c)
        val start = p.getLong("trial", 0L)
        if (start <= 0L) return 0L
        val now = System.currentTimeMillis()
        val seen = maxOf(p.getLong("seen", start), start)
        if (now < seen - 10 * 60 * 1000) return 0L
        if (now > seen) p.edit().putLong("seen", now).apply()
        return maxOf(0L, start + TRIAL_MS - now)
    }

    fun state(c: Context): State = when {
        isActivated(c) -> State.ACTIVE
        prefs(c).getLong("trial", 0L) <= 0L -> State.NEW
        trialLeft(c) > 0L -> State.TRIAL
        else -> State.EXPIRED
    }

    /** L'appli peut-elle jouer de la musique ? (activée ou essai en cours) */
    fun canUse(c: Context): Boolean = state(c).let { it == State.ACTIVE || it == State.TRIAL }

    fun buyMessage(c: Context): String =
        "Bonjour, je souhaite obtenir Tone Dial ($PRICE). Mon code appareil : ${deviceId(c)}"
}

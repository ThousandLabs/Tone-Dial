package com.thousandlabs.tonedial.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fonctions activables et désactivables dans les Réglages.
 * Chaque interrupteur est gardé sur le téléphone et suivi en direct par l'interface.
 */
object Features {

    class Toggle internal constructor(private val key: String, private val default: Boolean) {
        private val flow by lazy { MutableStateFlow(UserStore.getBool(key, default)) }
        val state: StateFlow<Boolean> get() = flow.asStateFlow()
        val on: Boolean get() = flow.value
        fun set(value: Boolean) {
            UserStore.putBool(key, value)
            flow.value = value
        }
    }

    /** Onglet « Animes » sur l'accueil. */
    val animeTab = Toggle("feat_anime_tab", true)
    /** Mix automatiques dans l'onglet Playlists. */
    val mixes = Toggle("feat_mixes", true)
    /** Bilan du mois. */
    val recap = Toggle("feat_recap", true)
    /** Traduction des paroles en français. */
    val translate = Toggle("feat_translate", true)
    /** Fondu enchaîné entre les titres. */
    val crossfade = Toggle("crossfade", false)
    /** Secouer le téléphone pour agir sur la lecture. */
    val shake = Toggle("shake", false)
    /** Petite vibration quand la secousse est prise en compte. */
    val shakeVibrate = Toggle("shake_vibrate", true)

    /** Durée du fondu enchaîné, en secondes (2 à 10). */
    fun crossfadeSeconds(): Int = UserStore.getInt("crossfade_sec", 5).coerceIn(2, 10)
    fun setCrossfadeSeconds(s: Int) = UserStore.putInt("crossfade_sec", s.coerceIn(2, 10))

    /** Sensibilité de la secousse : 0 faible, 1 moyenne, 2 forte. */
    fun shakeSensitivity(): Int = UserStore.getInt("shake_sens", 1).coerceIn(0, 2)
    fun setShakeSensitivity(v: Int) = UserStore.putInt("shake_sens", v.coerceIn(0, 2))

    /** Action de la secousse : 0 titre suivant, 1 lecture / pause. */
    fun shakeAction(): Int = UserStore.getInt("shake_action", 0).coerceIn(0, 1)
    fun setShakeAction(v: Int) = UserStore.putInt("shake_action", v.coerceIn(0, 1))

    /** Réglages oui / non à inclure dans la sauvegarde. */
    val backupBoolKeys = listOf("feat_anime_tab", "feat_mixes", "feat_recap", "feat_translate", "crossfade", "shake", "shake_vibrate", "lyrics_tr_show")
    val backupIntKeys = listOf("crossfade_sec", "shake_sens", "shake_action")
}

package com.thousandlabs.tonedial.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import com.thousandlabs.tonedial.data.UserStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Égaliseur, amplificateur de basses et effet d'espace, attachés à la session audio du lecteur.
 * Les réglages sont mémorisés et réappliqués à chaque démarrage.
 */
object AudioFx {

    data class State(
        val available: Boolean = false,
        val enabled: Boolean = false,
        val bandFreqs: List<Int> = emptyList(),   // Hz
        val bandLevels: List<Int> = emptyList(),  // millibels
        val minLevel: Int = -1500,
        val maxLevel: Int = 1500,
        val presets: List<String> = emptyList(),
        val currentPreset: Int = -1,
        val bass: Int = 0,          // 0..1000
        val virtualizer: Int = 0,   // 0..1000
        val bassSupported: Boolean = false,
        val virtualizerSupported: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var eq: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virt: Virtualizer? = null

    fun attach(sessionId: Int) {
        release()
        if (sessionId == 0) return
        runCatching {
            val e = Equalizer(0, sessionId)
            eq = e
            val bands = e.numberOfBands.toInt()
            val range = e.bandLevelRange
            val freqs = (0 until bands).map { e.getCenterFreq(it.toShort()) / 1000 }
            val presets = (0 until e.numberOfPresets.toInt()).map { localizePreset(e.getPresetName(it.toShort())) }
            val savedPreset = UserStore.getInt("eq_preset", -1)
            val levels = (0 until bands).map { i -> UserStore.getInt("eq_band_$i", 0) }
            if (savedPreset in presets.indices) {
                e.usePreset(savedPreset.toShort())
            } else {
                levels.forEachIndexed { i, l -> e.setBandLevel(i.toShort(), l.toShort()) }
            }
            val enabled = UserStore.getBool("eq_enabled", false)
            e.enabled = enabled
            _state.value = _state.value.copy(
                available = true,
                enabled = enabled,
                bandFreqs = freqs,
                bandLevels = (0 until bands).map { e.getBandLevel(it.toShort()).toInt() },
                minLevel = range[0].toInt(),
                maxLevel = range[1].toInt(),
                presets = presets,
                currentPreset = savedPreset,
            )
        }
        runCatching {
            val b = BassBoost(0, sessionId)
            bassBoost = b
            val strength = UserStore.getInt("bass", 0)
            if (b.strengthSupported) b.setStrength(strength.toShort())
            b.enabled = _state.value.enabled && strength > 0
            _state.value = _state.value.copy(bass = strength, bassSupported = b.strengthSupported)
        }
        runCatching {
            val v = Virtualizer(0, sessionId)
            virt = v
            val strength = UserStore.getInt("virtualizer", 0)
            if (v.strengthSupported) v.setStrength(strength.toShort())
            v.enabled = _state.value.enabled && strength > 0
            _state.value = _state.value.copy(virtualizer = strength, virtualizerSupported = v.strengthSupported)
        }
    }

    fun setEnabled(on: Boolean) {
        UserStore.putBool("eq_enabled", on)
        runCatching { eq?.enabled = on }
        runCatching { bassBoost?.enabled = on && _state.value.bass > 0 }
        runCatching { virt?.enabled = on && _state.value.virtualizer > 0 }
        _state.value = _state.value.copy(enabled = on)
    }

    fun setBand(index: Int, level: Int) {
        val e = eq ?: return
        runCatching { e.setBandLevel(index.toShort(), level.toShort()) }
        UserStore.putInt("eq_band_$index", level)
        UserStore.putInt("eq_preset", -1)
        val levels = _state.value.bandLevels.toMutableList()
        if (index in levels.indices) levels[index] = level
        _state.value = _state.value.copy(bandLevels = levels, currentPreset = -1)
    }

    fun usePreset(index: Int) {
        val e = eq ?: return
        runCatching { e.usePreset(index.toShort()) }
        UserStore.putInt("eq_preset", index)
        val levels = (0 until e.numberOfBands.toInt()).map { e.getBandLevel(it.toShort()).toInt() }
        levels.forEachIndexed { i, l -> UserStore.putInt("eq_band_$i", l) }
        _state.value = _state.value.copy(bandLevels = levels, currentPreset = index)
    }

    fun reset() {
        val n = _state.value.bandLevels.size
        UserStore.putInt("eq_preset", -1)
        for (i in 0 until n) setBand(i, 0)
        setBass(0)
        setVirtualizer(0)
    }

    fun setBass(strength: Int) {
        UserStore.putInt("bass", strength)
        runCatching {
            bassBoost?.setStrength(strength.toShort())
            bassBoost?.enabled = _state.value.enabled && strength > 0
        }
        _state.value = _state.value.copy(bass = strength)
    }

    fun setVirtualizer(strength: Int) {
        UserStore.putInt("virtualizer", strength)
        runCatching {
            virt?.setStrength(strength.toShort())
            virt?.enabled = _state.value.enabled && strength > 0
        }
        _state.value = _state.value.copy(virtualizer = strength)
    }

    fun release() {
        runCatching { eq?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virt?.release() }
        eq = null; bassBoost = null; virt = null
    }

    private fun localizePreset(name: String): String = when (name.lowercase()) {
        "normal" -> "Normal"
        "classical" -> "Classique"
        "dance" -> "Dance"
        "flat" -> "Plat"
        "folk" -> "Folk"
        "heavy metal" -> "Metal"
        "hip hop" -> "Hip-hop"
        "jazz" -> "Jazz"
        "pop" -> "Pop"
        "rock" -> "Rock"
        else -> name
    }
}

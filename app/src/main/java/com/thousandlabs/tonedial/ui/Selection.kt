package com.thousandlabs.tonedial.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.thousandlabs.tonedial.data.Playlist
import com.thousandlabs.tonedial.data.Song

/**
 * Sélection multiple de morceaux (appui long sur un titre).
 * `pool` est la liste affichée à l'écran : elle sert à « Tout sélectionner »
 * et à garder l'ordre d'affichage lors des ajouts.
 */
class Selection {
    var ids by mutableStateOf<Set<Long>>(emptySet())
        private set
    var pool by mutableStateOf<List<Song>>(emptyList())
        private set
    var playlist by mutableStateOf<Playlist?>(null)
        private set

    val active: Boolean get() = ids.isNotEmpty()
    val count: Int get() = ids.size
    val allSelected: Boolean get() = pool.isNotEmpty() && pool.all { it.id in ids }

    fun isSelected(id: Long) = id in ids

    fun toggle(song: Song, pool: List<Song>, playlist: Playlist?) {
        if (ids.isEmpty()) {
            this.pool = pool
            this.playlist = playlist
        }
        ids = if (song.id in ids) ids - song.id else ids + song.id
    }

    fun selectAll() {
        ids = if (allSelected) emptySet() else pool.map { it.id }.toSet()
    }

    /** Les morceaux sélectionnés, dans l'ordre de l'écran. */
    fun songs(): List<Song> = pool.filter { it.id in ids }

    fun clear() {
        ids = emptySet()
        pool = emptyList()
        playlist = null
    }
}

class UiFlags {
    var playerOpen by mutableStateOf(false)
}

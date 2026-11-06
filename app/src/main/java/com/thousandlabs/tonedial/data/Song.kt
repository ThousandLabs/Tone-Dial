package com.thousandlabs.tonedial.data

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val artistId: Long,
    val duration: Long,
    val track: Int,
    val year: Int,
    val path: String,
    val folder: String,
    val folderPath: String,
    val dateAdded: Long,
    val size: Long,
) {
    val uri: Uri get() = songUri(id)
    val artUri: Uri get() = albumArtUri(albumId)

    companion object {
        fun songUri(id: Long): Uri =
            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

        fun albumArtUri(albumId: Long): Uri =
            ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
    }
}

data class Album(
    val id: Long,
    val name: String,
    val artist: String,
    val songs: List<Song>,
) {
    val artUri: Uri get() = Song.albumArtUri(id)
}

data class Artist(
    val name: String,
    val songs: List<Song>,
) {
    val albumCount: Int get() = songs.map { it.albumId }.distinct().size
}

data class Folder(
    val name: String,
    val path: String,
    val songs: List<Song>,
)

data class Playlist(
    val id: Long,
    val name: String,
    val songIds: List<Long>,
)

enum class SongSort(val label: String) {
    TITLE("Titre"),
    ARTIST("Artiste"),
    ALBUM("Album"),
    DATE_ADDED("Date d'ajout"),
    DURATION("Durée"),
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

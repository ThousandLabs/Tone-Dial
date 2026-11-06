package com.thousandlabs.tonedial.data

import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object MusicRepository {

    suspend fun loadSongs(context: Context, minDurationMs: Long): List<Song> = withContext(Dispatchers.IO) {
        val songs = ArrayList<Song>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        @Suppress("DEPRECATION")
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
        val selection = "${MediaStore.Audio.Media.DURATION} >= ?"
        val args = arrayOf(minDurationMs.toString())

        try {
            context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val artistIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val yearCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                @Suppress("DEPRECATION")
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)

                while (c.moveToNext()) {
                    val path = c.getString(dataCol) ?: ""
                    val parent = File(path).parentFile
                    val rawTitle = c.getString(titleCol)
                    val displayName = c.getString(nameCol) ?: ""
                    val title = when {
                        !rawTitle.isNullOrBlank() -> rawTitle
                        displayName.isNotBlank() -> displayName.substringBeforeLast('.')
                        else -> "Sans titre"
                    }
                    songs += Song(
                        id = c.getLong(idCol),
                        title = title,
                        artist = cleanTag(c.getString(artistCol), "Artiste inconnu"),
                        album = cleanTag(c.getString(albumCol), "Album inconnu"),
                        albumId = c.getLong(albumIdCol),
                        artistId = c.getLong(artistIdCol),
                        duration = c.getLong(durCol),
                        track = c.getInt(trackCol) % 1000,
                        year = c.getInt(yearCol),
                        path = path,
                        folder = parent?.name ?: "Stockage",
                        folderPath = parent?.absolutePath ?: "",
                        dateAdded = c.getLong(dateCol),
                        size = c.getLong(sizeCol),
                    )
                }
            }
        } catch (e: SecurityException) {
            return@withContext emptyList()
        }
        songs
    }

    private fun cleanTag(value: String?, fallback: String): String =
        if (value.isNullOrBlank() || value == "<unknown>") fallback else value

    fun albums(songs: List<Song>): List<Album> =
        songs.groupBy { it.albumId }.map { (id, list) ->
            val sorted = list.sortedWith(compareBy<Song> { it.track }.thenBy { it.title.lowercase() })
            Album(
                id = id,
                name = sorted.first().album,
                artist = sorted.groupingBy { it.artist }.eachCount().maxByOrNull { it.value }?.key ?: "",
                songs = sorted,
            )
        }.sortedBy { it.name.lowercase() }

    fun artists(songs: List<Song>): List<Artist> =
        songs.groupBy { it.artist }.map { (name, list) ->
            Artist(name, list.sortedWith(compareBy<Song> { it.album.lowercase() }.thenBy { it.track }))
        }.sortedBy { it.name.lowercase() }

    fun folders(songs: List<Song>): List<Folder> =
        songs.groupBy { it.folderPath }.map { (path, list) ->
            Folder(list.first().folder, path, list.sortedBy { it.title.lowercase() })
        }.sortedBy { it.name.lowercase() }

    fun sort(songs: List<Song>, sort: SongSort): List<Song> = when (sort) {
        SongSort.TITLE -> songs.sortedBy { it.title.lowercase() }
        SongSort.ARTIST -> songs.sortedWith(compareBy<Song> { it.artist.lowercase() }.thenBy { it.title.lowercase() })
        SongSort.ALBUM -> songs.sortedWith(compareBy<Song> { it.album.lowercase() }.thenBy { it.track })
        SongSort.DATE_ADDED -> songs.sortedByDescending { it.dateAdded }
        SongSort.DURATION -> songs.sortedByDescending { it.duration }
    }
}

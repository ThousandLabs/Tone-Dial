package com.thousandlabs.tonedial.data

import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object MusicRepository {

    suspend fun loadSongs(context: Context, minDurationMs: Long, mode: ArtistMode = ArtistMode.FULL): List<Song> = withContext(Dispatchers.IO) {
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
                    val rawArtist = c.getString(artistCol)
                    var finalTitle = title
                    var finalArtist = cleanTag(rawArtist, "Artiste inconnu")
                    when (mode) {
                        ArtistMode.FULL -> {
                            val r = TagCleaner.clean(title, rawArtist)
                            finalTitle = r.title
                            finalArtist = r.artist ?: UNKNOWN
                        }
                        // titre intact : on se contente de repérer l'artiste
                        ArtistMode.GROUP -> finalArtist = TagCleaner.clean(title, rawArtist).artist ?: UNKNOWN
                        ArtistMode.OFF -> Unit
                    }
                    if (mode != ArtistMode.OFF) {
                        animeArtist(finalArtist)?.let { (anime, theme) ->
                            finalArtist = anime
                            if (mode == ArtistMode.FULL) finalTitle = "$theme - $finalTitle"
                        }
                    }
                    songs += Song(
                        id = c.getLong(idCol),
                        title = finalTitle,
                        artist = finalArtist,
                        artists = if (finalArtist == "Artiste inconnu") listOf(finalArtist) else TagCleaner.artists(finalArtist, finalTitle),
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
        inferArtists(songs, mode)
    }

    private const val UNKNOWN = "Artiste inconnu"

    private val animeTheme = Regex("""^(.+?)\s+((?:opening|op|ending|ed)\s*\d{1,3})$""", RegexOption.IGNORE_CASE)

    /** « One Piece Opening 25 » → anime « One Piece », thème « Opening 25 » : tous les openings d'un anime ensemble. */
    private fun animeArtist(artist: String): Pair<String, String>? {
        if (artist == UNKNOWN) return null
        val m = animeTheme.matchEntire(artist.trim()) ?: return null
        return m.groupValues[1].trim() to m.groupValues[2].trim().replaceFirstChar { it.uppercase() }
    }

    private val stopWords = setOf(
        "the", "a", "an", "le", "la", "les", "l", "un", "une", "des", "i", "my", "you", "we", "it", "in", "on",
        "of", "de", "du", "to", "and", "et", "je", "tu", "me", "mon", "ma", "mes", "this", "that", "no", "oh",
    )

    /**
     * Repère les artistes sans tiret : si plusieurs titres commencent par les mêmes mots
     * (« Eminem Lose Yourself », « Eminem Mockingbird »…), ou par le nom d'un artiste déjà connu,
     * ces mots sont l'artiste.
     */
    private fun inferArtists(songs: List<Song>, mode: ArtistMode): List<Song> {
        if (mode == ArtistMode.OFF) return songs
        val known = songs.asSequence().filter { it.artist != UNKNOWN }.flatMap { it.artists.asSequence() }
            .filter { it.length >= 2 }.distinctBy { it.lowercase() }.sortedByDescending { it.length }.toList()

        val unknown = songs.filter { it.artist == UNKNOWN }
        if (unknown.isEmpty()) return songs
        fun words(s: Song) = TagCleaner.prepare(s.title).split(Regex("""\s+""")).filter { it.isNotBlank() }
        val counts = HashMap<String, Pair<String, Int>>()
        unknown.forEach { s ->
            val w = words(s)
            if (w.isEmpty() || w[0].lowercase() in stopWords) return@forEach
            for (n in 1..3) {
                if (w.size <= n) break
                val prefix = w.take(n).joinToString(" ")
                val key = prefix.lowercase()
                val cur = counts[key]
                counts[key] = (cur?.first ?: prefix) to ((cur?.second ?: 0) + 1)
            }
        }

        return songs.map { s ->
            if (s.artist != UNKNOWN) return@map s
            val prepared = TagCleaner.prepare(s.title)
            var artist: String? = known.firstOrNull { k ->
                prepared.length > k.length + 1 && prepared.startsWith(k, ignoreCase = true) &&
                    !prepared[k.length].isLetterOrDigit()
            }?.let { prepared.substring(0, it.length) }
            if (artist == null) {
                val w = words(s)
                for (n in 3 downTo 1) {
                    if (w.size <= n) continue
                    val c = counts[w.take(n).joinToString(" ").lowercase()] ?: continue
                    if (c.second >= 3) {
                        artist = c.first
                        break
                    }
                }
            }
            val a = artist ?: return@map s
            val title = if (mode == ArtistMode.FULL) prepared.substring(a.length).trim(' ', '-', '–', '—', ':', '|').ifBlank { s.title } else s.title
            s.copy(artist = a, title = title, artists = TagCleaner.artists(a, title))
        }
    }

    private fun cleanTag(value: String?, fallback: String): String =
        if (TagCleaner.isUnknown(value)) fallback else value!!

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

    /** Un titre avec invités apparaît chez chacun de ses artistes. */
    fun artists(songs: List<Song>): List<Artist> {
        val byKey = LinkedHashMap<String, Pair<String, MutableList<Song>>>()
        songs.forEach { song ->
            song.artists.forEach { name ->
                val key = name.lowercase()
                val entry = byKey.getOrPut(key) { name to mutableListOf() }
                entry.second += song
            }
        }
        return byKey.values.map { (name, list) ->
            Artist(name, list.sortedWith(compareBy<Song> { it.album.lowercase() }.thenBy { it.track }))
        }.sortedWith(compareBy<Artist> { it.name == "Artiste inconnu" }.thenBy { it.name.lowercase() })
    }

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

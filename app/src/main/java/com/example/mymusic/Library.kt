package com.example.mymusic

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

@Entity(tableName = "tracks")
@androidx.compose.runtime.Immutable
data class Track(
    @PrimaryKey val id: Long,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val mime: String,
    val artUri: String? = null,
    val lyrics: String? = null,
    val fetchedAt: Long = 0,
    val genre: String = "",
    val addedAt: Long = 0,
    val needsOnline: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isAudiobook: Boolean = false
)

@Dao interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE") fun observe(): Flow<List<Track>>
    @Query("SELECT * FROM tracks WHERE id = :id") suspend fun get(id: Long): Track?
    @Query("SELECT * FROM tracks") suspend fun all(): List<Track>
    @Query("UPDATE tracks SET genre = :genre WHERE artist = :artist AND genre = ''")
    suspend fun fillGenreForArtist(artist: String, genre: String)
    @Upsert suspend fun put(track: Track)
    @Query("DELETE FROM tracks WHERE id NOT IN (:ids)") suspend fun prune(ids: List<Long>)
    @Query("DELETE FROM tracks") suspend fun clear()
    @Query("SELECT * FROM tracks ORDER BY addedAt DESC LIMIT :limit") suspend fun recent(limit: Int): List<Track>
}

@Entity(tableName = "plays", indices = [Index("trackId"), Index("playedAt")])
data class PlayEvent(@PrimaryKey(autoGenerate = true) val id: Long = 0, val trackId: Long, val playedAt: Long)

@Dao interface PlayDao {
    @Insert suspend fun put(event: PlayEvent)
    @Query("SELECT * FROM plays WHERE playedAt >= :since ORDER BY playedAt DESC") fun observeSince(since: Long): Flow<List<PlayEvent>>
    @Query("SELECT trackId, COUNT(*) AS count FROM plays GROUP BY trackId ORDER BY count DESC LIMIT :limit")
    suspend fun top(limit: Int): List<PlayCount>
}
data class PlayCount(val trackId: Long, val count: Int)

@Entity(tableName = "playlists")
data class Playlist(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val createdAt: Long)

@Entity(tableName = "playlist_entries", primaryKeys = ["playlistId", "trackId"])
data class PlaylistEntry(val playlistId: Long, val trackId: Long, val position: Int)

@Dao interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC") fun observe(): Flow<List<Playlist>>
    @Query("SELECT * FROM playlists") suspend fun all(): List<Playlist>
    @Insert suspend fun put(playlist: Playlist): Long
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM playlist_entries WHERE playlistId = :id ORDER BY position") fun entries(id: Long): Flow<List<PlaylistEntry>>
    @Query("SELECT * FROM playlist_entries WHERE playlistId = :id ORDER BY position") suspend fun entriesOnce(id: Long): List<PlaylistEntry>
    @Upsert suspend fun put(entry: PlaylistEntry)
    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun remove(playlistId: Long, trackId: Long)
    @Query("DELETE FROM playlist_entries WHERE playlistId = :id") suspend fun clear(id: Long)
}

@Entity(tableName = "eq_presets")
data class EqPreset(@PrimaryKey val name: String, val levels: String, val bass: Int)

@Dao interface EqDao {
    @Query("SELECT * FROM eq_presets ORDER BY name") fun observe(): Flow<List<EqPreset>>
    @Upsert suspend fun put(preset: EqPreset)
}

@Database(entities = [Track::class, EqPreset::class, PlayEvent::class, Playlist::class, PlaylistEntry::class],
    version = 4, exportSchema = false)
abstract class MusicDb : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun eq(): EqDao
    abstract fun plays(): PlayDao
    abstract fun playlists(): PlaylistDao
    companion object {
        @Volatile private var instance: MusicDb? = null
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN genre TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tracks ADD COLUMN addedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS plays (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, trackId INTEGER NOT NULL, playedAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_plays_trackId ON plays (trackId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_plays_playedAt ON plays (playedAt)")
                db.execSQL("CREATE TABLE IF NOT EXISTS playlists (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS playlist_entries (playlistId INTEGER NOT NULL, trackId INTEGER NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(playlistId, trackId))")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE tracks_new (id INTEGER NOT NULL PRIMARY KEY, uri TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, durationMs INTEGER NOT NULL, mime TEXT NOT NULL, artUri TEXT, lyrics TEXT, fetchedAt INTEGER NOT NULL, genre TEXT NOT NULL, addedAt INTEGER NOT NULL, needsOnline INTEGER NOT NULL)")
                db.execSQL("INSERT INTO tracks_new SELECT id, uri, title, artist, album, durationMs, mime, CASE WHEN artUri LIKE 'http%' THEN NULL ELSE artUri END, lyrics, 0, genre, addedAt, 1 FROM tracks")
                db.execSQL("DROP TABLE tracks")
                db.execSQL("ALTER TABLE tracks_new RENAME TO tracks")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN isAudiobook INTEGER NOT NULL DEFAULT 0")
            }
        }
        fun get(context: Context): MusicDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, MusicDb::class.java, "music.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }
    }
}

class LocalLibrary(private val context: Context, private val dao: TrackDao) {
    suspend fun scan() = withContext(Dispatchers.IO) {
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.RELATIVE_PATH
        )
        val tags = LocalTagReader(context)
        val ids = mutableListOf<Long>()
        val cursor = context.contentResolver.query(uri, projection, null, null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE") ?: return@withContext
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                ids += id
                val old = dao.get(id)
                val trackUri = ContentUris.withAppendedId(uri, id)
                val local = tags.read(trackUri, id, c.getString(8))
                val fileName = c.getString(7)?.substringBeforeLast('.')?.tagValue()
                val audiobook = c.getString(7)?.endsWith(".m4b", true) == true ||
                    c.getString(9)?.contains("audiobook", true) == true ||
                    local.genre?.contains("audiobook", true) == true ||
                    local.genre?.contains("spoken word", true) == true
                val online = old?.takeIf { it.fetchedAt > 0 }
                dao.put(Track(
                    id, trackUri.toString(),
                    local.title ?: online?.title.tagValue() ?: fileName ?: c.getString(1).tagValue() ?: "Unknown",
                    local.artist ?: online?.artist.tagValue() ?: c.getString(2).tagValue() ?: "Unknown",
                    local.album ?: online?.album.tagValue() ?: c.getString(3).tagValue().orEmpty(),
                    c.getLong(4), c.getString(5) ?: "",
                    local.artUri ?: old?.artUri, local.lyrics ?: old?.lyrics,
                    old?.fetchedAt ?: 0, local.genre ?: old?.genre.orEmpty(),
                    old?.addedAt?.takeIf { it > 0 } ?: c.getLong(6) * 1000,
                    local.title == null || local.artist == null || local.album == null || local.artUri == null,
                    audiobook
                ))
            }
        }
        if (ids.isNotEmpty()) dao.prune(ids) else dao.clear()
    }
}

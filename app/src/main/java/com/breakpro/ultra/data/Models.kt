package com.breakpro.ultra.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class ProjectType(val label: String) {
    VIDEO("Video Project"), PHOTO("Photo Project"), GIF("GIF Project"),
    ANIMATION("Animation Project"), AUDIO("Audio Project"), VECTOR("Vector Project"),
    POSTER("Poster Project"), THREE_D("3D Project"), PDF("PDF Project")
}

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "media_items",
    foreignKeys = [ForeignKey(
        entity = ProjectEntity::class, parentColumns = ["id"],
        childColumns = ["projectId"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("projectId")]
)
data class MediaItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val addedAt: Long = System.currentTimeMillis()
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Insert suspend fun insert(p: ProjectEntity): Long
    @Update suspend fun update(p: ProjectEntity)
    @Delete suspend fun delete(p: ProjectEntity)

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: Long): ProjectEntity?

    @Query("SELECT * FROM media_items WHERE projectId = :pid ORDER BY addedAt DESC")
    fun observeMedia(pid: Long): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE projectId = :pid")
    suspend fun mediaOf(pid: Long): List<MediaItemEntity>

    @Insert suspend fun insertMedia(m: MediaItemEntity): Long
    @Delete suspend fun deleteMedia(m: MediaItemEntity)
}

@Database(entities = [ProjectEntity::class, MediaItemEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        @Volatile private var inst: AppDatabase? = null
        fun get(ctx: android.content.Context): AppDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "breakpro.db")
                .build().also { inst = it }
        }
    }
}

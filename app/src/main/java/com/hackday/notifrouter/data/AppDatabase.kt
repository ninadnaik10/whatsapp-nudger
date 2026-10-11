package com.hackday.notifrouter.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Every message we've seen, the decision we made, and how the user reacted.
 * This is the on-device equivalent of message_history.csv + message_events.csv.
 */
@Entity(
    tableName = "messages",
    indices = [Index(value = ["dedupKey"], unique = true), Index("conversation"), Index("sender"), Index("sbnKey")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dedupKey: String,
    val sbnKey: String,
    val packageName: String,
    val receivedAt: Long,
    val messageTime: Long,
    val isGroup: Boolean,
    val conversation: String,
    val sender: String,
    val text: String,
    val mediaType: String,
    val senderIsContact: Boolean?,
    // Decision
    val action: String? = null,
    val messageType: String? = null,
    val reason: String? = null,
    val confidence: Float? = null,
    val evidenceIds: String? = null,
    val decisionSource: String? = null,
    val latencyMs: Long? = null,
    // Feedback
    /** opened | dismissed | read_in_app */
    val reaction: String? = null,
    /** Action the user says we should have taken. */
    val correction: String? = null,
    val digestCleared: Boolean = false,
    /** See ImageStatus: none | read | sent_to_gemma | missing | denied */
    val imageStatus: String? = null,
    /** WhatsApp chat JID, used by "Open chat". */
    val chatId: String? = null,
)

data class ReactionCount(val reaction: String?, val n: Int)

/** Aggregates for the Home impact card. */
data class DayStats(
    val total: Int,
    val notified: Int,
    val digested: Int,
    val muted: Int,
    /** Muted and classified as scam or spam. */
    val blocked: Int,
    /** Mean Gemma decision time; null when nothing went through the model. */
    val avgLlmLatencyMs: Double?,
) {
    val avoided: Int get() = digested + muted
}

@Dao
interface MessageDao {
    /** Returns -1 when the message was already stored (WhatsApp re-posts unread messages). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: MessageEntity): Long

    /** WhatsApp sometimes posts the same message under two notification keys with slightly different timestamps. */
    @Query(
        """SELECT * FROM messages WHERE packageName = :packageName AND conversation = :conversation AND sender = :sender
           AND text = :text AND ABS(messageTime - :messageTime) < :windowMs ORDER BY id LIMIT 1"""
    )
    suspend fun findNearDuplicate(
        packageName: String, conversation: String, sender: String, text: String, messageTime: Long, windowMs: Long,
    ): MessageEntity?

    @Query(
        """UPDATE messages SET action = :action, messageType = :messageType, reason = :reason,
           confidence = :confidence, evidenceIds = :evidenceIds, decisionSource = :source, latencyMs = :latencyMs,
           imageStatus = :imageStatus
           WHERE id = :id"""
    )
    suspend fun saveDecision(
        id: Long, action: String, messageType: String, reason: String, confidence: Float,
        evidenceIds: String, source: String, latencyMs: Long, imageStatus: String,
    )

    @Query(
        """SELECT * FROM messages
           WHERE id != :excludeId AND action IS NOT NULL AND (conversation = :conversation OR sender = :sender)
           ORDER BY receivedAt DESC LIMIT :limit"""
    )
    suspend fun history(excludeId: Long, conversation: String, sender: String, limit: Int): List<MessageEntity>

    @Query("SELECT reaction, COUNT(*) AS n FROM messages WHERE conversation = :conversation AND reaction IS NOT NULL GROUP BY reaction")
    suspend fun reactionCounts(conversation: String): List<ReactionCount>

    @Query("SELECT correction AS reaction, COUNT(*) AS n FROM messages WHERE conversation = :conversation AND correction IS NOT NULL GROUP BY correction")
    suspend fun correctionCounts(conversation: String): List<ReactionCount>

    @Query("SELECT COUNT(*) FROM messages WHERE action = :action AND receivedAt >= :since")
    suspend fun countActionSince(action: String, since: Long): Int

    @Query("UPDATE messages SET reaction = :reaction WHERE sbnKey = :sbnKey AND reaction IS NULL")
    suspend fun setReactionBySbnKey(sbnKey: String, reaction: String)

    @Query("UPDATE messages SET reaction = :reaction WHERE id = :id AND reaction IS NULL")
    suspend fun setReaction(id: Long, reaction: String)

    @Query("UPDATE messages SET correction = :correction WHERE id = :id")
    suspend fun setCorrection(id: Long, correction: String?)

    @Query(
        """SELECT COUNT(*) AS total,
           COALESCE(SUM(action = 'notify'), 0) AS notified,
           COALESCE(SUM(action = 'digest'), 0) AS digested,
           COALESCE(SUM(action = 'mute'), 0) AS muted,
           COALESCE(SUM(action = 'mute' AND messageType IN ('scam', 'spam')), 0) AS blocked,
           AVG(CASE WHEN decisionSource = 'llm' THEN latencyMs END) AS avgLlmLatencyMs
           FROM messages WHERE action IS NOT NULL AND receivedAt >= :since"""
    )
    fun statsSince(since: Long): Flow<DayStats>

    @Query("SELECT * FROM messages ORDER BY receivedAt DESC LIMIT 200")
    fun feed(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE action = 'digest' AND digestCleared = 0 ORDER BY receivedAt DESC")
    fun digest(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE action = 'digest' AND digestCleared = 0 ORDER BY receivedAt DESC LIMIT :limit")
    suspend fun pendingDigest(limit: Int): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE action = 'digest' AND digestCleared = 0")
    suspend fun pendingDigestCount(): Int

    @Query("UPDATE messages SET digestCleared = 1 WHERE action = 'digest'")
    suspend fun clearDigest()
}

@Database(entities = [MessageEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN imageStatus TEXT")
            }
        }

        /** Try-screen messages used to be stored; they're previews now, so drop the old rows. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM messages WHERE packageName = 'simulator'")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN chatId TEXT")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "notif-router.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}

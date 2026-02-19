package com.chatapp.data.api

import androidx.room.*
import com.chatapp.data.model.Conversation
import com.chatapp.data.model.Message
import kotlinx.coroutines.flow.Flow

// ==================== DAOs ====================

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC")
    fun getMessages(conversationId: String): Flow<List<Message>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<Message>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteConversationMessages(conversationId: String)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateStatus(messageId: String, status: String)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY lastMessageTime DESC")
    fun getConversations(): Flow<List<Conversation>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversations(conversations: List<Conversation>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: Conversation)

    @Query("UPDATE conversations SET lastMessage = :lastMessage, lastMessageTime = :time WHERE id = :id")
    suspend fun updateLastMessage(id: String, lastMessage: String, time: Long)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :id")
    suspend fun clearUnread(id: String)

    @Query("UPDATE conversations SET isOnline = :online WHERE participantId = :userId")
    suspend fun updateUserOnline(userId: String, online: Boolean)
}

// ==================== DATABASE ====================

@Database(
    entities = [Message::class, Conversation::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
}

class Converters {
    @TypeConverter
    fun fromMessageType(value: com.chatapp.data.model.MessageType): String = value.name

    @TypeConverter
    fun toMessageType(value: String): com.chatapp.data.model.MessageType =
        com.chatapp.data.model.MessageType.valueOf(value)

    @TypeConverter
    fun fromMessageStatus(value: com.chatapp.data.model.MessageStatus): String = value.name

    @TypeConverter
    fun toMessageStatus(value: String): com.chatapp.data.model.MessageStatus =
        com.chatapp.data.model.MessageStatus.valueOf(value)
}

package MailAggregator.MailAggregator.telegram.repository

import MailAggregator.MailAggregator.telegram.repository.jpa.TelegramLogMessageJpaEntity
import MailAggregator.MailAggregator.telegram.repository.jpa.TelegramLogMessageJpaRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class TelegramLogMessageRepository(
    private val jpa: TelegramLogMessageJpaRepository,
) {
    fun save(householdId: UUID, chatId: Long, messageId: Long, transactionId: String) {
        jpa.save(
            TelegramLogMessageJpaEntity(
                id = UUID.randomUUID(),
                householdId = householdId,
                chatId = chatId,
                messageId = messageId,
                transactionId = transactionId,
            ),
        )
    }

    fun findByChatAndMessage(chatId: Long, messageId: Long): TelegramLogMessageJpaEntity? =
        jpa.findByChatIdAndMessageId(chatId, messageId)

    fun upsertComment(chatId: Long, messageId: Long, comment: String) {
        val existing = jpa.findByChatIdAndMessageId(chatId, messageId) ?: return
        jpa.save(existing.copy(comment = comment))
    }
}

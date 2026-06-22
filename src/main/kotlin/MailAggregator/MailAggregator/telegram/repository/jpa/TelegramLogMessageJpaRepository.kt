package MailAggregator.MailAggregator.telegram.repository.jpa

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TelegramLogMessageJpaRepository : JpaRepository<TelegramLogMessageJpaEntity, UUID> {
    fun findByChatIdAndMessageId(chatId: Long, messageId: Long): TelegramLogMessageJpaEntity?
    fun findAllByTransactionId(transactionId: String): List<TelegramLogMessageJpaEntity>
}

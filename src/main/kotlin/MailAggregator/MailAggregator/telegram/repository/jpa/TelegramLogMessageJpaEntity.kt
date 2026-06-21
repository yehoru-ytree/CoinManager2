package MailAggregator.MailAggregator.telegram.repository.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "telegram_log_message", schema = "bankaggregator")
data class TelegramLogMessageJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "chat_id", nullable = false)
    val chatId: Long,

    @Column(name = "message_id", nullable = false)
    val messageId: Long,

    @Column(name = "transaction_id", nullable = false)
    val transactionId: String,

    @Column(name = "comment")
    val comment: String? = null,
)

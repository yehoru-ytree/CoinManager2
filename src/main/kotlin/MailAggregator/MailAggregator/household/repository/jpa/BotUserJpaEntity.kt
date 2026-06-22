package MailAggregator.MailAggregator.household.repository.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "bot_user", schema = "bankaggregator")
data class BotUserJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "chat_id", nullable = false)
    val chatId: Long,

    @Column(name = "name")
    val name: String? = null,

    @Column(name = "household_id", nullable = false)
    val householdId: UUID,
)

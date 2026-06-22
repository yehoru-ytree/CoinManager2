package MailAggregator.MailAggregator.household.repository.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "invite_token", schema = "bankaggregator")
data class InviteTokenJpaEntity(
    @Id
    val token: String,

    @Column(name = "household_id", nullable = false)
    val householdId: UUID,

    @Column(name = "used_at")
    val usedAt: Instant? = null,

    @Column(name = "used_by_chat_id")
    val usedByChatId: Long? = null,
)

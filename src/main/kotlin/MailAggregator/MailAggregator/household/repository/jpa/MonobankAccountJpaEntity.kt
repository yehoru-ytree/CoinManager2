package MailAggregator.MailAggregator.household.repository.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "monobank_account", schema = "bankaggregator")
data class MonobankAccountJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "token", nullable = false)
    val token: String,

    @Column(name = "account_id", nullable = false)
    val accountId: String,
)

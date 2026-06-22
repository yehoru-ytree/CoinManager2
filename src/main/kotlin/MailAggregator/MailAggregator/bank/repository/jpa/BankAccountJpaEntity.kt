package MailAggregator.MailAggregator.bank.repository.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "bank_account", schema = "bankaggregator")
data class BankAccountJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "bank_type", nullable = false)
    val bankType: String,

    @Column(name = "token", nullable = false)
    val token: String,

    @Column(name = "account_id", nullable = false)
    val accountId: String,

    @Column(name = "client_id")
    val clientId: String? = null,
)

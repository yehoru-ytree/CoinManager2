package MailAggregator.MailAggregator.bank.repository.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "transaction", schema = "bankaggregator")
data class TransactionJpaEntity(
    @Id
    val id: String,

    @Column(name = "household_id", nullable = false)
    val householdId: UUID,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long,

    @Column(name = "description", nullable = false)
    val description: String,

    @Column(name = "tx_time", nullable = false)
    val time: Long,

    @Column(name = "amount", nullable = false)
    val amount: Long,

    @Column(name = "currency_code", nullable = false)
    val currencyCode: Int,

    @Column(name = "comment")
    val comment: String? = null,

    @Column(name = "counter_name")
    val counterName: String? = null,
)

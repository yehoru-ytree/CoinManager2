package MailAggregator.MailAggregator.bank.repository.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "transaction_status", schema = "bankaggregator")
data class TransactionStatusJpaEntity(
    @Id
    @Column(name = "transaction_id", nullable = false)
    val transactionId: String,

    @Column(name = "status", nullable = false)
    val status: String,

    /**
     * Category this transaction was merged into, for EXECUTED rows. Null for every other status,
     * and for EXECUTED rows written before V11 — the link simply wasn't recorded back then.
     */
    @Column(name = "category_id")
    val categoryId: UUID? = null,
)

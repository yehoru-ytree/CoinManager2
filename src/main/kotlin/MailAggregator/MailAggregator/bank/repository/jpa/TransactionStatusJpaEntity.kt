package MailAggregator.MailAggregator.bank.repository.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "transaction_status", schema = "bankaggregator")
data class TransactionStatusJpaEntity(
    @Id
    @Column(name = "transaction_id", nullable = false)
    val transactionId: String,

    @Column(name = "status", nullable = false)
    val status: String,
)

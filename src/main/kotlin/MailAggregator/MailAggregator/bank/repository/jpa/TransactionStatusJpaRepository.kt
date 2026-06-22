package MailAggregator.MailAggregator.bank.repository.jpa

import org.springframework.data.jpa.repository.JpaRepository

interface TransactionStatusJpaRepository : JpaRepository<TransactionStatusJpaEntity, String> {
    fun findAllByStatus(status: String): List<TransactionStatusJpaEntity>
    fun findByTransactionId(transactionId: String): TransactionStatusJpaEntity?
}

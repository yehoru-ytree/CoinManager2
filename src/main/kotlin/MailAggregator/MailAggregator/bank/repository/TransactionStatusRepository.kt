package MailAggregator.MailAggregator.bank.repository

import MailAggregator.MailAggregator.bank.TransactionStatus
import MailAggregator.MailAggregator.bank.repository.jpa.TransactionStatusJpaEntity
import MailAggregator.MailAggregator.bank.repository.jpa.TransactionStatusJpaRepository
import org.springframework.stereotype.Service

@Service
class TransactionStatusRepository(
    val transactionStatusJpaRepository: TransactionStatusJpaRepository,
) {
    fun save(transactions: Map<String, TransactionStatus>) {
        transactionStatusJpaRepository.saveAll(
            transactions.map { TransactionStatusJpaEntity(it.key, it.value.name) },
        )
    }

    fun getAll(): List<TransactionStatusJpaEntity> = transactionStatusJpaRepository.findAll()

    fun get(transactionId: String) = transactionStatusJpaRepository.findById(transactionId)

    fun getReceivedTransactions(): List<TransactionStatusJpaEntity> =
        transactionStatusJpaRepository.findAllByStatus(TransactionStatus.RECEIVED.name)

    fun findByTransactionId(transactionId: String): TransactionStatus? =
        transactionStatusJpaRepository.findByTransactionId(transactionId)?.let { TransactionStatus.fromString(it.status) }
}

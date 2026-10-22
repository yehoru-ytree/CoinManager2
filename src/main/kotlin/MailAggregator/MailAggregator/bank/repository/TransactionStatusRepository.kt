package MailAggregator.MailAggregator.bank.repository

import MailAggregator.MailAggregator.bank.TransactionStatus
import MailAggregator.MailAggregator.bank.repository.jpa.TransactionStatusJpaEntity
import MailAggregator.MailAggregator.bank.repository.jpa.TransactionStatusJpaRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class TransactionStatusRepository(
    val transactionStatusJpaRepository: TransactionStatusJpaRepository,
) {
    /**
     * Write a status with no category attached. Any previously stored `categoryId` is dropped,
     * which is what every non-EXECUTED status wants: RECEIVED / PENDING_APPROVAL / IGNORED all
     * mean "not currently booked into a category".
     */
    fun save(transactions: Map<String, TransactionStatus>) {
        transactionStatusJpaRepository.saveAll(
            transactions.map { TransactionStatusJpaEntity(it.key, it.value.name) },
        )
    }

    /**
     * Mark transactions EXECUTED and remember which category each was merged into, so the amount
     * can later be subtracted from that exact sheet row if the categorisation is undone
     * (reply «Удалить» — see RecategorizeWizard).
     */
    fun saveExecuted(categorized: Map<String, UUID>) {
        transactionStatusJpaRepository.saveAll(
            categorized.map {
                TransactionStatusJpaEntity(it.key, TransactionStatus.EXECUTED.name, it.value)
            },
        )
    }

    fun getAll(): List<TransactionStatusJpaEntity> = transactionStatusJpaRepository.findAll()

    fun get(transactionId: String) = transactionStatusJpaRepository.findById(transactionId)

    fun getReceivedTransactions(): List<TransactionStatusJpaEntity> =
        transactionStatusJpaRepository.findAllByStatus(TransactionStatus.RECEIVED.name)

    fun findByTransactionId(transactionId: String): TransactionStatus? =
        transactionStatusJpaRepository.findByTransactionId(transactionId)?.let { TransactionStatus.fromString(it.status) }

    /**
     * Category this transaction is currently booked into, or null — either because it isn't
     * EXECUTED, or because it was executed before V11 started recording the link.
     */
    fun findCategoryId(transactionId: String): UUID? =
        transactionStatusJpaRepository.findByTransactionId(transactionId)?.categoryId
}

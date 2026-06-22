package MailAggregator.MailAggregator.bank.repository

import MailAggregator.MailAggregator.bank.Transaction
import MailAggregator.MailAggregator.bank.repository.jpa.TransactionJpaEntity
import MailAggregator.MailAggregator.bank.repository.jpa.TransactionJpaRepository
import org.springframework.stereotype.Service

@Service
class TransactionRepository(
    val transactionJpaRepository: TransactionJpaRepository,
) {
    fun save(transactions: List<Transaction>) {
        transactionJpaRepository.saveAll(transactions.map { it.toEntity() })
    }

    fun getAll(): List<Transaction> =
        transactionJpaRepository.findAll().map { it.toDomain() }

    fun get(transactionId: String): java.util.Optional<Transaction> =
        transactionJpaRepository.findById(transactionId).map { it.toDomain() }

    fun existsById(transactionId: String): Boolean =
        transactionJpaRepository.existsById(transactionId)

    fun findAllById(transactionIds: List<String>): List<Transaction> =
        transactionJpaRepository.findAllById(transactionIds).map { it.toDomain() }

    private fun Transaction.toEntity() = TransactionJpaEntity(
        id = id,
        householdId = householdId,
        createdAt = createdAt,
        description = description,
        time = time,
        amount = amount,
        currencyCode = currencyCode,
        comment = comment,
        counterName = counterName,
    )

    private fun TransactionJpaEntity.toDomain() = Transaction(
        id = id,
        householdId = householdId,
        createdAt = createdAt,
        description = description,
        time = time,
        amount = amount,
        currencyCode = currencyCode,
        comment = comment,
        counterName = counterName,
    )
}

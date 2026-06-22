package MailAggregator.MailAggregator.monobank.repository

import MailAggregator.MailAggregator.monobank.api.MonoApiTransaction
import MailAggregator.MailAggregator.monobank.application.MonoTransaction
import MailAggregator.MailAggregator.monobank.repository.jpa.TransactionJpaEntity
import MailAggregator.MailAggregator.monobank.repository.jpa.TransactionJpaRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Service

@Service
class TransactionRepository(
    val transactionJpaRepository: TransactionJpaRepository,
) {
    companion object {
        val objectMapper = jacksonObjectMapper()
    }

    fun save(transactions: List<MonoTransaction>) {
        transactionJpaRepository.saveAll(
            transactions.map {
                TransactionJpaEntity(
                    id = it.id,
                    householdId = it.householdId,
                    createdAt = it.createdAt,
                    raw = objectMapper.valueToTree(it.raw),
                )
            },
        )
    }

    fun getAll() = transactionJpaRepository.findAll().map { it.toDomain() }

    fun get(transactionId: String) = transactionJpaRepository.findById(transactionId).map { it.toDomain() }

    fun existsById(transactionId: String) = transactionJpaRepository.existsById(transactionId)

    fun findAllById(transactionIds: List<String>) =
        transactionJpaRepository.findAllById(transactionIds).map { it.toDomain() }

    private fun TransactionJpaEntity.toDomain() = MonoTransaction(
        id = id,
        householdId = householdId,
        createdAt = createdAt,
        raw = objectMapper.treeToValue(raw, MonoApiTransaction::class.java),
    )
}

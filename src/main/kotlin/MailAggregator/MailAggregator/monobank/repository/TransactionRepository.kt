package MailAggregator.MailAggregator.monobank.repository

import MailAggregator.MailAggregator.monobank.api.MonoApiTransaction
import MailAggregator.MailAggregator.monobank.application.MonoTransaction
import MailAggregator.MailAggregator.monobank.application.TransactionStatus
import MailAggregator.MailAggregator.monobank.repository.jpa.TransactionJpaEntity
import MailAggregator.MailAggregator.monobank.repository.jpa.TransactionJpaRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class TransactionRepository(
    val transactionJpaRepository: TransactionJpaRepository
) {
    companion object {
        val objectMapper = jacksonObjectMapper()
    }

    fun save(transactions: List<MonoTransaction>) {
        transactionJpaRepository.saveAll(
            transactions.map {
                TransactionJpaEntity(
                    it.id,
                    it.createdAt,
                    objectMapper.valueToTree(it.raw),
                )
            }
        )
    }

    fun getAll() = transactionJpaRepository.findAll().map { it ->
        MonoTransaction(
            id = it.id,
            createdAt = it.createdAt,
            raw = objectMapper.treeToValue(it.raw, MonoApiTransaction::class.java),
        )
    }

    fun get(transactionId: String) = transactionJpaRepository.findById(transactionId).map {
        MonoTransaction(
            id = it.id,
            createdAt = it.createdAt,
            raw = objectMapper.treeToValue(it.raw, MonoApiTransaction::class.java),
        )
    }

    fun existsById(transactionId: String) = transactionJpaRepository.existsById(transactionId)

    fun findAllById(transactionIds: List<String>) = transactionJpaRepository.findAllById(transactionIds).map { it ->
        MonoTransaction(
            id = it.id,
            createdAt = it.createdAt,
            raw = objectMapper.treeToValue(it.raw, MonoApiTransaction::class.java),
        )
    }
}
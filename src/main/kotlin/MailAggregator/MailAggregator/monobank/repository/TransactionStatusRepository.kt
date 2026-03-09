package MailAggregator.MailAggregator.monobank.repository

import MailAggregator.MailAggregator.monobank.api.MonoApiTransaction
import MailAggregator.MailAggregator.monobank.application.MonoTransaction
import MailAggregator.MailAggregator.monobank.application.TransactionStatus
import MailAggregator.MailAggregator.monobank.repository.jpa.TransactionJpaEntity
import MailAggregator.MailAggregator.monobank.repository.jpa.TransactionJpaRepository
import MailAggregator.MailAggregator.monobank.repository.jpa.TransactionStatusJpaEntity
import MailAggregator.MailAggregator.monobank.repository.jpa.TransactionStatusJpaRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class TransactionStatusRepository(
    val transactionStatusJpaRepository: TransactionStatusJpaRepository
) {
    companion object {
        val objectMapper = jacksonObjectMapper()
    }

    fun save(transactions: Map<String, TransactionStatus>) {
        transactionStatusJpaRepository.saveAll(
            transactions.map {
                TransactionStatusJpaEntity(
                    it.key,
                    it.value.toString(),
                )
            }
        )
    }

    fun getAll() = transactionStatusJpaRepository.findAll()

    fun get(transactionId: UUID) = transactionStatusJpaRepository.findById(transactionId)

}
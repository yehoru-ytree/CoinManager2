package MailAggregator.MailAggregator.spreadsheet.usecases

import MailAggregator.MailAggregator.monobank.application.MonoTransaction
import MailAggregator.MailAggregator.monobank.application.TransactionStatus
import MailAggregator.MailAggregator.monobank.repository.TransactionRepository
import MailAggregator.MailAggregator.monobank.repository.TransactionStatusRepository
import jakarta.transaction.Transactional

open class HandleNotProcessedTransactionsUseCase(
    val transactionRepository: TransactionRepository,
    val transactionStatusRepository: TransactionStatusRepository,
) {
    @Transactional
    open operator fun invoke(transactions: List<MonoTransaction>): List<MonoTransaction> {
        if (transactions.isEmpty()) return emptyList()
        val existingIds = transactionRepository.findAllById(transactions.map(MonoTransaction::id))
            .map(MonoTransaction::id)
            .toSet()

        val newTransactions = transactions.filterNot { it.id in existingIds }

        transactionRepository.save(newTransactions)
        transactionStatusRepository.save(
            newTransactions.associate { it.id to TransactionStatus.RECEIVED }
        )

        val received = transactionStatusRepository.getReceivedTransactions()

        return transactionRepository.findAllById(received.map { it.transactionId })
    }
}
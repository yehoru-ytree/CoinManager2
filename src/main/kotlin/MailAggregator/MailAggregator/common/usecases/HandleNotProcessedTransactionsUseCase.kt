package MailAggregator.MailAggregator.spreadsheet.usecases

import MailAggregator.MailAggregator.bank.Transaction
import MailAggregator.MailAggregator.bank.TransactionStatus
import MailAggregator.MailAggregator.bank.repository.TransactionRepository
import MailAggregator.MailAggregator.bank.repository.TransactionStatusRepository
import jakarta.transaction.Transactional

open class HandleNotProcessedTransactionsUseCase(
    val transactionRepository: TransactionRepository,
    val transactionStatusRepository: TransactionStatusRepository,
) {
    @Transactional
    open operator fun invoke(transactions: List<Transaction>): List<Transaction> {
        if (transactions.isEmpty()) return emptyList()
        val existingIds = transactionRepository.findAllById(transactions.map(Transaction::id))
            .map(Transaction::id)
            .toSet()

        val newTransactions = transactions.filterNot { it.id in existingIds }

        transactionRepository.save(newTransactions)
        transactionStatusRepository.save(
            newTransactions.associate { it.id to TransactionStatus.RECEIVED },
        )

        val received = transactionStatusRepository.getReceivedTransactions()

        return transactionRepository.findAllById(received.map { it.transactionId })
    }
}

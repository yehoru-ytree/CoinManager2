package MailAggregator.MailAggregator.common.usecases

import MailAggregator.MailAggregator.bank.Transaction
import MailAggregator.MailAggregator.bank.TransactionStatus
import MailAggregator.MailAggregator.bank.repository.TransactionStatusRepository

class ExecuteTransactionsUseCase(
    val transactionStatusRepository: TransactionStatusRepository,
) {
    operator fun invoke(transactions: List<Transaction>) {
        val ids = transactions.map { it.id }
        transactionStatusRepository.save(ids.associateWith { TransactionStatus.EXECUTED })
    }
}

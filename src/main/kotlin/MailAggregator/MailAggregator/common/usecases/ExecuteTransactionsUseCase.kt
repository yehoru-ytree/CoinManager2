package MailAggregator.MailAggregator.common.usecases

import MailAggregator.MailAggregator.monobank.application.MonoTransaction
import MailAggregator.MailAggregator.monobank.application.TransactionStatus
import MailAggregator.MailAggregator.monobank.repository.TransactionStatusRepository

class ExecuteTransactionsUseCase(
    val transactionStatusRepository: TransactionStatusRepository
) {
    operator fun invoke(transactions: List<MonoTransaction>){
        val ids = transactions.map { it.id }
        transactionStatusRepository.save(
            ids.associateWith { TransactionStatus.EXECUTED }
        )
    }
}
package MailAggregator.MailAggregator.spreadsheet.usecases

import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.common.Month
import MailAggregator.MailAggregator.monobank.api.MonobankApi
import MailAggregator.MailAggregator.monobank.application.MonoTransaction
import MailAggregator.MailAggregator.monobank.application.TransactionStatus
import MailAggregator.MailAggregator.monobank.repository.TransactionRepository
import MailAggregator.MailAggregator.monobank.repository.TransactionStatusRepository
import MailAggregator.MailAggregator.spreadsheet.util.ExcelUtil
import MailAggregator.MailAggregator.spreadsheet.util.SheetRequester
import jakarta.annotation.PostConstruct
import jakarta.transaction.Transactional
import java.math.BigDecimal
import java.time.LocalDate

open class HandleIncomingTransactionUseCase(
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
            newTransactions.associate { it.id to TransactionStatus.PENDING }
        )

        return newTransactions
    }
}
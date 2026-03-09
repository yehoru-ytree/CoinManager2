package MailAggregator.MailAggregator.common.usecases

import MailAggregator.MailAggregator.common.config.Config.Companion.TIME_ZONE
import MailAggregator.MailAggregator.monobank.application.TransactionStatus
import MailAggregator.MailAggregator.monobank.repository.TransactionRepository
import MailAggregator.MailAggregator.monobank.repository.TransactionStatusRepository
import MailAggregator.MailAggregator.spreadsheet.usecases.MergeSpendingsByDateUseCase
import MailAggregator.MailAggregator.telegram.CategorizationBot
import java.time.Instant

class HandleTelegramResponseUseCase(
    val transactionRepository: TransactionRepository,
    val transactionStatusRepository: TransactionStatusRepository,
    val mergeSpendingsByDateUseCase: MergeSpendingsByDateUseCase,
) {

    operator fun invoke(transactionId: String, decision: CategorizationBot.Decision) {
        if (decision is CategorizationBot.Decision.Ignore) {
            transactionStatusRepository.save(
                mapOf(transactionId to TransactionStatus.IGNORED)
            )
        } else if (decision is CategorizationBot.Decision.Category) {
            val transaction = transactionRepository.get(transactionId) ?: return

            val date = Instant.ofEpochSecond(transaction.get().raw.time)
                .atZone(TIME_ZONE)
                .toLocalDate()

            mergeSpendingsByDateUseCase(
                date = date,
                newExpenses = mapOf(
                    decision.category to transaction.get().raw.amount.toDouble() * -1 / 100.0
                )
            )
        }
    }
}
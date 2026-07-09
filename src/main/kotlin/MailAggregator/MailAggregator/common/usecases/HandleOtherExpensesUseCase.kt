package MailAggregator.MailAggregator.common.usecases

import MailAggregator.MailAggregator.bank.Transaction
import MailAggregator.MailAggregator.bank.TransactionStatus
import MailAggregator.MailAggregator.bank.repository.TransactionStatusRepository
import MailAggregator.MailAggregator.common.config.Config.Companion.TIME_ZONE
import MailAggregator.MailAggregator.telegram.CategorizationBot
import MailAggregator.MailAggregator.telegram.model.CategorizationRequest
import java.time.Instant

class HandleOtherExpensesUseCase(
    val telegramBot: CategorizationBot,
    val transactionStatusRepository: TransactionStatusRepository,
) {
    operator fun invoke(uncategorizedTransactions: List<Transaction>) {
        for (transaction in uncategorizedTransactions) {
            telegramBot.promptHousehold(
                CategorizationRequest(
                    transactionId = transaction.id,
                    householdId = transaction.householdId,
                    amount = (transaction.amount / 100.0).toString() + " ₴",
                    description = transaction.description,
                    transactionTime = Instant.ofEpochSecond(transaction.time)
                        .atZone(TIME_ZONE)
                        .toLocalDateTime().toString(),
                ),
            )
            transactionStatusRepository.save(
                mapOf(transaction.id to TransactionStatus.PENDING_APPROVAL),
            )
        }
    }
}

package MailAggregator.MailAggregator.common.usecases

import MailAggregator.MailAggregator.bank.TransactionStatus
import MailAggregator.MailAggregator.bank.repository.TransactionRepository
import MailAggregator.MailAggregator.bank.repository.TransactionStatusRepository
import MailAggregator.MailAggregator.common.config.Config.Companion.TIME_ZONE
import MailAggregator.MailAggregator.household.repository.HouseholdRepository
import MailAggregator.MailAggregator.spreadsheet.usecases.MergeSpendingsByDateUseCase
import MailAggregator.MailAggregator.telegram.CategorizationBot
import java.time.Instant

class HandleTelegramResponseUseCase(
    val transactionRepository: TransactionRepository,
    val transactionStatusRepository: TransactionStatusRepository,
    val mergeSpendingsByDateUseCase: MergeSpendingsByDateUseCase,
    val householdRepository: HouseholdRepository,
) {

    operator fun invoke(transactionId: String, decision: CategorizationBot.Decision) {
        if (decision is CategorizationBot.Decision.Ignore) {
            transactionStatusRepository.save(
                mapOf(transactionId to TransactionStatus.IGNORED),
            )
        } else if (decision is CategorizationBot.Decision.Category) {
            val transaction = transactionRepository.get(transactionId).orElse(null) ?: return
            val household = householdRepository.findHousehold(transaction.householdId) ?: return

            val date = Instant.ofEpochSecond(transaction.time)
                .atZone(TIME_ZONE)
                .toLocalDate()

            mergeSpendingsByDateUseCase(
                household = household,
                date = date,
                newExpenses = mapOf(
                    decision.categoryId to transaction.amount.toDouble() * -1 / 100.0,
                ),
            )

            transactionStatusRepository.save(mapOf(transactionId to TransactionStatus.EXECUTED))
        }
    }
}

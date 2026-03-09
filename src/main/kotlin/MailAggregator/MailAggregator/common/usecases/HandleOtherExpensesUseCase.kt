package MailAggregator.MailAggregator.common.usecases

import MailAggregator.MailAggregator.common.config.Config.Companion.TIME_ZONE
import MailAggregator.MailAggregator.monobank.application.MonoTransaction
import MailAggregator.MailAggregator.telegram.CategorizationBot
import MailAggregator.MailAggregator.telegram.model.CategorizationRequest
import java.time.Instant

class HandleOtherExpensesUseCase(
    val telegramBot: CategorizationBot,
) {
    operator fun invoke(uncategorizedTransactions: List<MonoTransaction>) {
        for (transaction in uncategorizedTransactions) {
            telegramBot.sendTx(
                CategorizationRequest(
                    transactionId = transaction.id,
                    amount = (transaction.raw.amount/100.0).toString()+" ₴",
                    description = transaction.raw.description,
                    transactionTime = Instant.ofEpochSecond(transaction.raw.time)
                        .atZone(TIME_ZONE)
                        .toLocalDateTime().toString()
                )

            )
        }
    }
}
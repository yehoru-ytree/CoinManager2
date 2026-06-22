package MailAggregator.MailAggregator.common.usecases

import MailAggregator.MailAggregator.common.config.Config.Companion.TIME_ZONE
import MailAggregator.MailAggregator.household.repository.HouseholdRepository
import MailAggregator.MailAggregator.monobank.repository.TransactionRepository
import MailAggregator.MailAggregator.spreadsheet.usecases.AppendCommentToSheetUseCase
import MailAggregator.MailAggregator.telegram.repository.TelegramLogMessageRepository
import java.time.Instant

class HandleTelegramCommentUseCase(
    private val telegramLogMessageRepository: TelegramLogMessageRepository,
    private val transactionRepository: TransactionRepository,
    private val appendCommentToSheetUseCase: AppendCommentToSheetUseCase,
    private val householdRepository: HouseholdRepository,
) {
    operator fun invoke(chatId: Long, replyToMessageId: Long, comment: String): Boolean {
        val record = telegramLogMessageRepository.findByChatAndMessage(chatId, replyToMessageId)
            ?: return false
        val transaction = transactionRepository.get(record.transactionId).orElse(null) ?: return false
        val household = householdRepository.findHousehold(transaction.householdId) ?: return false

        val date = Instant.ofEpochSecond(transaction.raw.time)
            .atZone(TIME_ZONE)
            .toLocalDate()

        appendCommentToSheetUseCase(household, date, comment)
        telegramLogMessageRepository.upsertComment(chatId, replyToMessageId, comment)
        return true
    }
}

package MailAggregator.MailAggregator

import MailAggregator.MailAggregator.spreadsheet.usecases.ProcessIncomingBankTransactionsUseCase
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class ScheduledTasks(
    val processIncomingBankTransactionsUseCase: ProcessIncomingBankTransactionsUseCase,
) {
    @Scheduled(fixedDelayString = "\${monobank.poll-interval}")
    fun scheduledTask() {
        processIncomingBankTransactionsUseCase()
    }
}

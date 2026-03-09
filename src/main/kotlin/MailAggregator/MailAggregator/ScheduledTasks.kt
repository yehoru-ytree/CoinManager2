package MailAggregator.MailAggregator

import MailAggregator.MailAggregator.monobank.api.MonobankApi
import MailAggregator.MailAggregator.spreadsheet.usecases.ProcessIncomingMonobankTransactionsUseCase
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.text.SimpleDateFormat


@Component
class ScheduledTasks(
    val processIncomingMonobankTransactionsUseCase: ProcessIncomingMonobankTransactionsUseCase
)  {

    @Scheduled(fixedDelayString = "PT5M")
    fun scheduledTask(){
        processIncomingMonobankTransactionsUseCase()
    }
}
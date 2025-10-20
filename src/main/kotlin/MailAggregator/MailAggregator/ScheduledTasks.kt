package MailAggregator.MailAggregator

import MailAggregator.MailAggregator.monobank.api.MonobankApi
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.text.SimpleDateFormat


@Component
class ScheduledTasks(
    val monoBankApi: MonobankApi
)  {

    val log: Logger = LoggerFactory.getLogger(ScheduledTasks::class.java)

    val dateFormat: SimpleDateFormat = SimpleDateFormat("HH:mm:ss")

    //@Scheduled(fixedRate = 60000)
    fun reportCurrentTime() {
        log.info(monoBankApi.getClientInfo().toString())
    }
}
package MailAggregator.MailAggregator

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class MailAggregatorApplication

fun main(args: Array<String>) {
	java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
	runApplication<MailAggregatorApplication>(*args)
}

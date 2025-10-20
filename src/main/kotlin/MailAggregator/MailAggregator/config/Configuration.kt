package MailAggregator.MailAggregator.config

import MailAggregator.MailAggregator.monobank.api.MonobankApi
import MailAggregator.MailAggregator.monobank.repository.TransactionRepository
import MailAggregator.MailAggregator.monobank.repository.jpa.TransactionJpaRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class Configuration {
    @Bean
    fun monobankApi(
        @Value("\${monobank.token}") token: String
    ) = MonobankApi(
        token = token
    )

    @Bean
    fun transactionRepository(
        transactionJpaRepository: TransactionJpaRepository
    ) = TransactionRepository(transactionJpaRepository)
}
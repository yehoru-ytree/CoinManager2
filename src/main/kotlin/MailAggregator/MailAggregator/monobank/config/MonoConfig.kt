package MailAggregator.MailAggregator.monobank.config

import MailAggregator.MailAggregator.monobank.api.MonobankApi
import MailAggregator.MailAggregator.monobank.repository.TransactionRepository
import MailAggregator.MailAggregator.monobank.repository.TransactionStatusRepository
import MailAggregator.MailAggregator.monobank.repository.jpa.TransactionJpaRepository
import MailAggregator.MailAggregator.monobank.repository.jpa.TransactionStatusJpaRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class MonoConfig {
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

    @Bean
    fun transactionStatusRepository(
        transactionStatusJpaRepository: TransactionStatusJpaRepository
    ) = TransactionStatusRepository(transactionStatusJpaRepository)
}
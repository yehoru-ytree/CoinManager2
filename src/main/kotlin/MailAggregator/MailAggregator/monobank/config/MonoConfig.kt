package MailAggregator.MailAggregator.monobank.config

import MailAggregator.MailAggregator.monobank.repository.TransactionRepository
import MailAggregator.MailAggregator.monobank.repository.TransactionStatusRepository
import MailAggregator.MailAggregator.monobank.repository.jpa.TransactionJpaRepository
import MailAggregator.MailAggregator.monobank.repository.jpa.TransactionStatusJpaRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class MonoConfig {
    @Bean
    fun transactionRepository(
        transactionJpaRepository: TransactionJpaRepository,
    ) = TransactionRepository(transactionJpaRepository)

    @Bean
    fun transactionStatusRepository(
        transactionStatusJpaRepository: TransactionStatusJpaRepository,
    ) = TransactionStatusRepository(transactionStatusJpaRepository)
}

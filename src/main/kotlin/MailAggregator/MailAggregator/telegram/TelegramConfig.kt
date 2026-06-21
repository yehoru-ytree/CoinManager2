package MailAggregator.MailAggregator.telegram

import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.common.usecases.AddCategoryUseCase
import MailAggregator.MailAggregator.common.usecases.HandleTelegramResponseUseCase
import MailAggregator.MailAggregator.monobank.repository.TransactionRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TelegramConfig {
    @Bean
    fun categorizationBot(
        handleTelegramResponseUseCase: HandleTelegramResponseUseCase,
        categoryRepository: CategoryRepository,
        addCategoryUseCase: AddCategoryUseCase,
        transactionRepository: TransactionRepository,
        @Value("\${telegram.bot-token}") botToken: String,
        @Value("\${telegram.owner-chat-id}") ownerChatId: String,
    ) = CategorizationBot(
        token = botToken,
        ownerChatId = ownerChatId.toLong(),
        categoryRepository = categoryRepository,
        addCategoryUseCase = addCategoryUseCase,
        transactionRepository = transactionRepository,
        onDecision = { transactionId, decision ->
            handleTelegramResponseUseCase(transactionId, decision)
        },
    )
}

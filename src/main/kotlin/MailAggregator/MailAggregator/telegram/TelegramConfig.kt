package MailAggregator.MailAggregator.telegram

import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.common.usecases.AddCategoryUseCase
import MailAggregator.MailAggregator.common.usecases.HandleTelegramCommentUseCase
import MailAggregator.MailAggregator.common.usecases.HandleTelegramResponseUseCase
import MailAggregator.MailAggregator.common.usecases.SaveKeywordUseCase
import MailAggregator.MailAggregator.monobank.repository.TransactionRepository
import MailAggregator.MailAggregator.telegram.repository.TelegramLogMessageRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TelegramConfig {
    @Bean
    fun categorizationBot(
        handleTelegramResponseUseCase: HandleTelegramResponseUseCase,
        handleTelegramCommentUseCase: HandleTelegramCommentUseCase,
        saveKeywordUseCase: SaveKeywordUseCase,
        categoryRepository: CategoryRepository,
        addCategoryUseCase: AddCategoryUseCase,
        transactionRepository: TransactionRepository,
        telegramLogMessageRepository: TelegramLogMessageRepository,
        messageSource: MessageSource,
        @Value("\${telegram.bot-token}") botToken: String,
        @Value("\${telegram.owner-chat-id}") ownerChatId: String,
    ) = CategorizationBot(
        token = botToken,
        ownerChatId = ownerChatId.toLong(),
        categoryRepository = categoryRepository,
        addCategoryUseCase = addCategoryUseCase,
        transactionRepository = transactionRepository,
        telegramLogMessageRepository = telegramLogMessageRepository,
        handleTelegramCommentUseCase = handleTelegramCommentUseCase,
        saveKeywordUseCase = saveKeywordUseCase,
        messageSource = messageSource,
        onDecision = { transactionId, decision ->
            handleTelegramResponseUseCase(transactionId, decision)
        },
    )
}

package MailAggregator.MailAggregator.telegram

import MailAggregator.MailAggregator.common.usecases.HandleTelegramResponseUseCase
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TelegramConfig {

    @Bean
    fun categorizationBot(
        handleTelegramResponseUseCase: HandleTelegramResponseUseCase,
        @Value("\${telegram.bot-token}") botToken: String,
        @Value("\${telegram.owner-chat-id}") ownerChatId: Long,
    ) = CategorizationBot(
        token = botToken,
        ownerChatId = ownerChatId,
        onDecision = { transactionId, decision ->
            handleTelegramResponseUseCase(transactionId, decision)
        },
    )

}
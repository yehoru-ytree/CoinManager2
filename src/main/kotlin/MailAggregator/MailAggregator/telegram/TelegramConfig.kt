package MailAggregator.MailAggregator.telegram

import MailAggregator.MailAggregator.common.usecases.HandleTelegramResponseUseCase
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TelegramConfig {
    companion object {
        const val BOT_TOKEN = "8365494163:AAHSje7t8XfsZ7PIiZb70STcQWKMmt-nlqk"
        val CHAT_IDS = listOf(
            368934876L
        )
    }

    @Bean
    fun categorizationBot(
        handleTelegramResponseUseCase: HandleTelegramResponseUseCase
    ) = CategorizationBot(
        token = BOT_TOKEN,
        ownerChatId = CHAT_IDS.first(),
        onDecision = { transactionId, decision ->
            handleTelegramResponseUseCase(transactionId, decision)
        },
    )

}
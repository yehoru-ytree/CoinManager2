package MailAggregator.MailAggregator.telegram

import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.common.usecases.AddCategoryUseCase
import MailAggregator.MailAggregator.common.usecases.HandleTelegramCommentUseCase
import MailAggregator.MailAggregator.common.usecases.HandleTelegramResponseUseCase
import MailAggregator.MailAggregator.common.usecases.SaveKeywordUseCase
import MailAggregator.MailAggregator.household.repository.HouseholdRepository
import MailAggregator.MailAggregator.household.repository.InviteTokenRepository
import MailAggregator.MailAggregator.household.usecase.AddMonobankAccountUseCase
import MailAggregator.MailAggregator.household.usecase.CreateHouseholdUseCase
import MailAggregator.MailAggregator.household.usecase.JoinHouseholdUseCase
import MailAggregator.MailAggregator.monobank.repository.TransactionRepository
import MailAggregator.MailAggregator.monobank.repository.TransactionStatusRepository
import MailAggregator.MailAggregator.telegram.repository.TelegramLogMessageRepository
import org.springframework.beans.factory.annotation.Value
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
        transactionStatusRepository: TransactionStatusRepository,
        telegramLogMessageRepository: TelegramLogMessageRepository,
        householdRepository: HouseholdRepository,
        createHouseholdUseCase: CreateHouseholdUseCase,
        joinHouseholdUseCase: JoinHouseholdUseCase,
        addMonobankAccountUseCase: AddMonobankAccountUseCase,
        inviteTokenRepository: InviteTokenRepository,
        @Value("\${telegram.bot-token}") botToken: String,
    ) = CategorizationBot(
        token = botToken,
        categoryRepository = categoryRepository,
        addCategoryUseCase = addCategoryUseCase,
        transactionRepository = transactionRepository,
        telegramLogMessageRepository = telegramLogMessageRepository,
        handleTelegramCommentUseCase = handleTelegramCommentUseCase,
        saveKeywordUseCase = saveKeywordUseCase,
        transactionStatusRepository = transactionStatusRepository,
        householdRepository = householdRepository,
        createHouseholdUseCase = createHouseholdUseCase,
        joinHouseholdUseCase = joinHouseholdUseCase,
        addMonobankAccountUseCase = addMonobankAccountUseCase,
        inviteTokenRepository = inviteTokenRepository,
        onDecision = { transactionId, decision ->
            handleTelegramResponseUseCase(transactionId, decision)
        },
    )
}

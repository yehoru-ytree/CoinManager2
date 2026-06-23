package MailAggregator.MailAggregator.telegram

import MailAggregator.MailAggregator.bank.repository.TransactionRepository
import MailAggregator.MailAggregator.bank.repository.TransactionStatusRepository
import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.common.usecases.AddCashTransactionUseCase
import MailAggregator.MailAggregator.common.usecases.AddCategoryUseCase
import MailAggregator.MailAggregator.common.usecases.HandleTelegramCommentUseCase
import MailAggregator.MailAggregator.common.usecases.HandleTelegramResponseUseCase
import MailAggregator.MailAggregator.common.usecases.SaveKeywordUseCase
import MailAggregator.MailAggregator.household.repository.HouseholdRepository
import MailAggregator.MailAggregator.household.repository.InviteTokenRepository
import MailAggregator.MailAggregator.household.usecase.AddBankAccountUseCase
import MailAggregator.MailAggregator.household.usecase.CreateHouseholdUseCase
import MailAggregator.MailAggregator.household.usecase.JoinHouseholdUseCase
import MailAggregator.MailAggregator.monobank.api.MonobankApi
import MailAggregator.MailAggregator.spreadsheet.Authentication
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
        transactionStatusRepository: TransactionStatusRepository,
        telegramLogMessageRepository: TelegramLogMessageRepository,
        householdRepository: HouseholdRepository,
        createHouseholdUseCase: CreateHouseholdUseCase,
        joinHouseholdUseCase: JoinHouseholdUseCase,
        addBankAccountUseCase: AddBankAccountUseCase,
        addCashTransactionUseCase: AddCashTransactionUseCase,
        inviteTokenRepository: InviteTokenRepository,
        authentication: Authentication,
        monobankApi: MonobankApi,
        messageSource: MessageSource,
        @Value("\${telegram.bot-token}") botToken: String,
        @Value("\${email.imap.user:}") ingestEmail: String,
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
        addBankAccountUseCase = addBankAccountUseCase,
        addCashTransactionUseCase = addCashTransactionUseCase,
        inviteTokenRepository = inviteTokenRepository,
        authentication = authentication,
        monobankApi = monobankApi,
        ingestEmail = ingestEmail,
        messageSource = messageSource,
        onDecision = { transactionId, decision ->
            handleTelegramResponseUseCase(transactionId, decision)
        },
    )
}

package MailAggregator.MailAggregator.telegram

import MailAggregator.MailAggregator.common.config.Config
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
import MailAggregator.MailAggregator.telegram.wizard.AddCardWizard
import MailAggregator.MailAggregator.telegram.wizard.AddCategoryWizard
import MailAggregator.MailAggregator.telegram.wizard.CashEntryWizard
import MailAggregator.MailAggregator.telegram.wizard.CreateHouseholdWizard
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TelegramConfig {

    @Bean
    fun telegramGateway(
        @Value("\${telegram.bot-token}") botToken: String,
    ): TelegramGateway = PengradTelegramGateway(botToken)

    // The bot itself only holds the broadcast surface (sendTx / sendLog / notifyChat).
    // Wizards + PlainCommandHandler + UpdateRouter reference bot's broadcast methods via
    // constructor lambdas, so they must be created *after* it — Spring resolves that ordering.
    @Bean
    fun categorizationBot(
        telegramGateway: TelegramGateway,
        categoryRepository: CategoryRepository,
        householdRepository: HouseholdRepository,
        telegramLogMessageRepository: TelegramLogMessageRepository,
        messageSource: MessageSource,
    ) = CategorizationBot(
        gateway = telegramGateway,
        categoryRepository = categoryRepository,
        householdRepository = householdRepository,
        telegramLogMessageRepository = telegramLogMessageRepository,
        messageSource = messageSource,
    )

    @Bean
    fun cashEntryWizard(
        telegramGateway: TelegramGateway,
        addCashTransactionUseCase: AddCashTransactionUseCase,
        messageSource: MessageSource,
        categorizationBot: CategorizationBot,
    ) = CashEntryWizard(
        gateway = telegramGateway,
        addCashTransactionUseCase = addCashTransactionUseCase,
        messageSource = messageSource,
        zoneId = Config.TIME_ZONE,
        broadcastTx = categorizationBot::sendTx,
    )

    @Bean
    fun createHouseholdWizard(
        telegramGateway: TelegramGateway,
        householdRepository: HouseholdRepository,
        createHouseholdUseCase: CreateHouseholdUseCase,
        authentication: Authentication,
        messageSource: MessageSource,
    ) = CreateHouseholdWizard(
        gateway = telegramGateway,
        householdRepository = householdRepository,
        createHouseholdUseCase = createHouseholdUseCase,
        authentication = authentication,
        messageSource = messageSource,
    )

    @Bean
    fun addCategoryWizard(
        telegramGateway: TelegramGateway,
        categoryRepository: CategoryRepository,
        addCategoryUseCase: AddCategoryUseCase,
        householdRepository: HouseholdRepository,
        messageSource: MessageSource,
    ) = AddCategoryWizard(
        gateway = telegramGateway,
        categoryRepository = categoryRepository,
        addCategoryUseCase = addCategoryUseCase,
        householdRepository = householdRepository,
        messageSource = messageSource,
    )

    @Bean
    fun addCardWizard(
        telegramGateway: TelegramGateway,
        addBankAccountUseCase: AddBankAccountUseCase,
        monobankApi: MonobankApi,
        householdRepository: HouseholdRepository,
        messageSource: MessageSource,
        @Value("\${email.imap.user:}") ingestEmail: String,
    ) = AddCardWizard(
        gateway = telegramGateway,
        addBankAccountUseCase = addBankAccountUseCase,
        monobankApi = monobankApi,
        householdRepository = householdRepository,
        messageSource = messageSource,
        ingestEmail = ingestEmail,
    )

    @Bean
    fun plainCommandHandler(
        telegramGateway: TelegramGateway,
        transactionRepository: TransactionRepository,
        transactionStatusRepository: TransactionStatusRepository,
        telegramLogMessageRepository: TelegramLogMessageRepository,
        handleTelegramCommentUseCase: HandleTelegramCommentUseCase,
        saveKeywordUseCase: SaveKeywordUseCase,
        categoryRepository: CategoryRepository,
        householdRepository: HouseholdRepository,
        inviteTokenRepository: InviteTokenRepository,
        joinHouseholdUseCase: JoinHouseholdUseCase,
        messageSource: MessageSource,
        handleTelegramResponseUseCase: HandleTelegramResponseUseCase,
        categorizationBot: CategorizationBot,
    ) = PlainCommandHandler(
        gateway = telegramGateway,
        transactionRepository = transactionRepository,
        transactionStatusRepository = transactionStatusRepository,
        telegramLogMessageRepository = telegramLogMessageRepository,
        handleTelegramCommentUseCase = handleTelegramCommentUseCase,
        saveKeywordUseCase = saveKeywordUseCase,
        categoryRepository = categoryRepository,
        householdRepository = householdRepository,
        inviteTokenRepository = inviteTokenRepository,
        joinHouseholdUseCase = joinHouseholdUseCase,
        messageSource = messageSource,
        onDecision = { transactionId, decision ->
            handleTelegramResponseUseCase(transactionId, decision)
        },
        sendLog = categorizationBot::sendLog,
    )

    @Bean
    fun updateRouter(
        telegramGateway: TelegramGateway,
        householdRepository: HouseholdRepository,
        plainCommandHandler: PlainCommandHandler,
        createHouseholdWizard: CreateHouseholdWizard,
        addCategoryWizard: AddCategoryWizard,
        addCardWizard: AddCardWizard,
        cashEntryWizard: CashEntryWizard,
        messageSource: MessageSource,
    ) = UpdateRouter(
        gateway = telegramGateway,
        householdRepository = householdRepository,
        plainCommands = plainCommandHandler,
        publicWizards = listOf(createHouseholdWizard),
        registeredWizards = listOf(addCategoryWizard, addCardWizard, cashEntryWizard),
        messageSource = messageSource,
    )
}

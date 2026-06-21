package MailAggregator.MailAggregator.common.config

import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.common.usecases.AddCategoryUseCase
import MailAggregator.MailAggregator.common.usecases.CategorizeExpenseUseCase
import MailAggregator.MailAggregator.common.usecases.HandleTelegramResponseUseCase
import MailAggregator.MailAggregator.common.usecases.ExecuteTransactionsUseCase
import MailAggregator.MailAggregator.common.usecases.HandleOtherExpensesUseCase
import MailAggregator.MailAggregator.monobank.api.MonobankApi
import MailAggregator.MailAggregator.monobank.repository.TransactionRepository
import MailAggregator.MailAggregator.monobank.repository.TransactionStatusRepository
import MailAggregator.MailAggregator.spreadsheet.util.SheetRequester
import MailAggregator.MailAggregator.spreadsheet.usecases.GetSpendingsByDateUseCase
import MailAggregator.MailAggregator.spreadsheet.usecases.HandleNotProcessedTransactionsUseCase
import MailAggregator.MailAggregator.spreadsheet.usecases.MergeSpendingsByDateUseCase
import MailAggregator.MailAggregator.spreadsheet.usecases.ProcessIncomingMonobankTransactionsUseCase
import MailAggregator.MailAggregator.spreadsheet.usecases.UpdateSpendingsByDateUseCase
import MailAggregator.MailAggregator.telegram.CategorizationBot
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.ZoneId

@Configuration
class Config(
    @Value("\${app.timezone}") timezone: String,
) {
    init {
        TIME_ZONE = ZoneId.of(timezone)
    }

    companion object {
        // Initialised from app.timezone in Config's constructor; default kept for tests/static access before Spring boots.
        var TIME_ZONE: ZoneId = ZoneId.of("Europe/Zaporozhye")
            private set
    }

    @Bean
    fun categorizeExpenseUseCase(
        categoryRepository: CategoryRepository,
    ) = CategorizeExpenseUseCase(
        categoryRepository = categoryRepository,
    )

    @Bean
    fun addCategoryUseCase(
        categoryRepository: CategoryRepository,
        sheetRequester: SheetRequester,
        @Value("\${google.sheet-id}") sheetId: String,
        @Value("\${google.template-sheet-title}") templateSheetTitle: String,
    ) = AddCategoryUseCase(
        categoryRepository = categoryRepository,
        sheetRequester = sheetRequester,
        sheetId = sheetId,
        templateSheetTitle = templateSheetTitle,
    )

    @Bean
    fun handleIncomingTransactionUseCase(
        transactionRepository: TransactionRepository,
        transactionStatusRepository: TransactionStatusRepository,
    ) = HandleNotProcessedTransactionsUseCase(
        transactionRepository = transactionRepository,
        transactionStatusRepository = transactionStatusRepository
    )

    @Bean
    fun processIncomingMonobankTransactionsUseCase(
        monobankApi: MonobankApi,
        handleNotProcessedTransactionsUseCase: HandleNotProcessedTransactionsUseCase,
        categorizeExpenseUseCase: CategorizeExpenseUseCase,
        mergeSpendingsByDateUseCase: MergeSpendingsByDateUseCase,
        executeTransactionsUseCase: ExecuteTransactionsUseCase,
        handleOtherExpensesUseCase: HandleOtherExpensesUseCase,
        categoryRepository: CategoryRepository,
        categorizationBot: CategorizationBot,
        @Value("\${monobank.account-id}") accountId: String,
        @Value("\${monobank.statement-window-minutes}") statementWindowMinutes: Long,
    ) = ProcessIncomingMonobankTransactionsUseCase(
        monobankApi = monobankApi,
        handleNotProcessedTransactionsUseCase = handleNotProcessedTransactionsUseCase,
        categorizeExpenseUseCase = categorizeExpenseUseCase,
        executeTransactionsUseCase = executeTransactionsUseCase,
        mergeSpendingsByDateUseCase = mergeSpendingsByDateUseCase,
        handleOtherExpensesUseCase = handleOtherExpensesUseCase,
        categoryRepository = categoryRepository,
        categorizationBot = categorizationBot,
        accountId = accountId,
        statementWindowMinutes = statementWindowMinutes,
    )

    @Bean
    fun updateTransactionStatusUseCase(
        transactionStatusRepository: TransactionStatusRepository
    ) = ExecuteTransactionsUseCase(
        transactionStatusRepository = transactionStatusRepository
    )

    @Bean
    fun handleTelegramResponseUseCase(
        transactionRepository: TransactionRepository,
        mergeSpendingsByDateUseCase: MergeSpendingsByDateUseCase,
        transactionStatusRepository: TransactionStatusRepository
    ) = HandleTelegramResponseUseCase(
        transactionRepository = transactionRepository,
        transactionStatusRepository = transactionStatusRepository,
        mergeSpendingsByDateUseCase = mergeSpendingsByDateUseCase
    )

    @Bean
    fun mergeSpendingsByDateUseCase(
        updateSpendingsByDateUseCase: UpdateSpendingsByDateUseCase,
        getSpendingsByDateUseCase: GetSpendingsByDateUseCase,
    ) = MergeSpendingsByDateUseCase(
        updateSpendingsByDateUseCase = updateSpendingsByDateUseCase,
        getSpendingsByDateUseCase = getSpendingsByDateUseCase
    )

    @Bean
    fun handleOtherExpensesUseCase(
        categorizationBot: CategorizationBot,
        transactionStatusRepository: TransactionStatusRepository
    ) = HandleOtherExpensesUseCase(
        telegramBot = categorizationBot,
        transactionStatusRepository = transactionStatusRepository
    )
}
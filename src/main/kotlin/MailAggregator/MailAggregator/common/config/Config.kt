package MailAggregator.MailAggregator.common.config

import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.common.usecases.AddCategoryUseCase
import MailAggregator.MailAggregator.common.usecases.CategorizeExpenseUseCase
import MailAggregator.MailAggregator.common.usecases.ExecuteTransactionsUseCase
import MailAggregator.MailAggregator.common.usecases.HandleOtherExpensesUseCase
import MailAggregator.MailAggregator.common.usecases.HandleTelegramCommentUseCase
import MailAggregator.MailAggregator.common.usecases.HandleTelegramResponseUseCase
import MailAggregator.MailAggregator.common.usecases.SaveKeywordUseCase
import MailAggregator.MailAggregator.household.repository.HouseholdRepository
import MailAggregator.MailAggregator.monobank.api.MonobankApi
import MailAggregator.MailAggregator.monobank.repository.TransactionRepository
import MailAggregator.MailAggregator.monobank.repository.TransactionStatusRepository
import MailAggregator.MailAggregator.spreadsheet.usecase.VerifyMonthSheetExistsUseCase
import MailAggregator.MailAggregator.spreadsheet.usecases.AppendCommentToSheetUseCase
import MailAggregator.MailAggregator.spreadsheet.usecases.GetSpendingsByDateUseCase
import MailAggregator.MailAggregator.spreadsheet.usecases.HandleNotProcessedTransactionsUseCase
import MailAggregator.MailAggregator.spreadsheet.usecases.MergeSpendingsByDateUseCase
import MailAggregator.MailAggregator.spreadsheet.usecases.ProcessIncomingMonobankTransactionsUseCase
import MailAggregator.MailAggregator.spreadsheet.usecases.UpdateSpendingsByDateUseCase
import MailAggregator.MailAggregator.spreadsheet.util.SheetRequester
import MailAggregator.MailAggregator.telegram.CategorizationBot
import MailAggregator.MailAggregator.telegram.repository.TelegramLogMessageRepository
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
    ) = AddCategoryUseCase(
        categoryRepository = categoryRepository,
        sheetRequester = sheetRequester,
    )

    @Bean
    fun handleIncomingTransactionUseCase(
        transactionRepository: TransactionRepository,
        transactionStatusRepository: TransactionStatusRepository,
    ) = HandleNotProcessedTransactionsUseCase(
        transactionRepository = transactionRepository,
        transactionStatusRepository = transactionStatusRepository,
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
        householdRepository: HouseholdRepository,
        categorizationBot: CategorizationBot,
        @Value("\${monobank.statement-window-minutes}") statementWindowMinutes: Long,
    ) = ProcessIncomingMonobankTransactionsUseCase(
        monobankApi = monobankApi,
        handleNotProcessedTransactionsUseCase = handleNotProcessedTransactionsUseCase,
        categorizeExpenseUseCase = categorizeExpenseUseCase,
        executeTransactionsUseCase = executeTransactionsUseCase,
        mergeSpendingsByDateUseCase = mergeSpendingsByDateUseCase,
        handleOtherExpensesUseCase = handleOtherExpensesUseCase,
        categoryRepository = categoryRepository,
        householdRepository = householdRepository,
        categorizationBot = categorizationBot,
        statementWindowMinutes = statementWindowMinutes,
    )

    @Bean
    fun updateTransactionStatusUseCase(
        transactionStatusRepository: TransactionStatusRepository,
    ) = ExecuteTransactionsUseCase(
        transactionStatusRepository = transactionStatusRepository,
    )

    @Bean
    fun handleTelegramResponseUseCase(
        transactionRepository: TransactionRepository,
        mergeSpendingsByDateUseCase: MergeSpendingsByDateUseCase,
        transactionStatusRepository: TransactionStatusRepository,
        householdRepository: HouseholdRepository,
    ) = HandleTelegramResponseUseCase(
        transactionRepository = transactionRepository,
        transactionStatusRepository = transactionStatusRepository,
        mergeSpendingsByDateUseCase = mergeSpendingsByDateUseCase,
        householdRepository = householdRepository,
    )

    @Bean
    fun mergeSpendingsByDateUseCase(
        updateSpendingsByDateUseCase: UpdateSpendingsByDateUseCase,
        getSpendingsByDateUseCase: GetSpendingsByDateUseCase,
    ) = MergeSpendingsByDateUseCase(
        updateSpendingsByDateUseCase = updateSpendingsByDateUseCase,
        getSpendingsByDateUseCase = getSpendingsByDateUseCase,
    )

    @Bean
    fun handleOtherExpensesUseCase(
        categorizationBot: CategorizationBot,
        transactionStatusRepository: TransactionStatusRepository,
    ) = HandleOtherExpensesUseCase(
        telegramBot = categorizationBot,
        transactionStatusRepository = transactionStatusRepository,
    )

    @Bean
    fun appendCommentToSheetUseCase(
        sheetRequester: SheetRequester,
        verifyMonthSheetExistsUseCase: VerifyMonthSheetExistsUseCase,
    ) = AppendCommentToSheetUseCase(
        sheetRequester = sheetRequester,
        verifyMonthSheetExistsUseCase = verifyMonthSheetExistsUseCase,
    )

    @Bean
    fun handleTelegramCommentUseCase(
        telegramLogMessageRepository: TelegramLogMessageRepository,
        transactionRepository: TransactionRepository,
        appendCommentToSheetUseCase: AppendCommentToSheetUseCase,
        householdRepository: HouseholdRepository,
    ) = HandleTelegramCommentUseCase(
        telegramLogMessageRepository = telegramLogMessageRepository,
        transactionRepository = transactionRepository,
        appendCommentToSheetUseCase = appendCommentToSheetUseCase,
        householdRepository = householdRepository,
    )

    @Bean
    fun saveKeywordUseCase(
        categoryRepository: CategoryRepository,
    ) = SaveKeywordUseCase(
        categoryRepository = categoryRepository,
    )
}

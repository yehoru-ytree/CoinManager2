package MailAggregator.MailAggregator.common.config

import MailAggregator.MailAggregator.common.usecases.CategorizeExpenseUseCase
import MailAggregator.MailAggregator.common.usecases.HandleTelegramResponseUseCase
import MailAggregator.MailAggregator.common.usecases.ExecuteTransactionsUseCase
import MailAggregator.MailAggregator.common.usecases.HandleOtherExpensesUseCase
import MailAggregator.MailAggregator.monobank.api.MonobankApi
import MailAggregator.MailAggregator.monobank.repository.TransactionRepository
import MailAggregator.MailAggregator.monobank.repository.TransactionStatusRepository
import MailAggregator.MailAggregator.spreadsheet.usecases.GetSpendingsByDateUseCase
import MailAggregator.MailAggregator.spreadsheet.usecases.HandleIncomingTransactionUseCase
import MailAggregator.MailAggregator.spreadsheet.usecases.MergeSpendingsByDateUseCase
import MailAggregator.MailAggregator.spreadsheet.usecases.ProcessIncomingMonobankTransactionsUseCase
import MailAggregator.MailAggregator.spreadsheet.usecases.UpdateSpendingsByDateUseCase
import MailAggregator.MailAggregator.telegram.CategorizationBot
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.ZoneId

@Configuration
class Config {
    companion object{
        val TIME_ZONE = ZoneId.of("Europe/Zaporozhye")
    }

    @Bean
    fun categorizeExpenseUseCase() = CategorizeExpenseUseCase()

    @Bean
    fun handleIncomingTransactionUseCase(
        transactionRepository: TransactionRepository,
        transactionStatusRepository: TransactionStatusRepository,
    ) = HandleIncomingTransactionUseCase(
        transactionRepository = transactionRepository,
        transactionStatusRepository = transactionStatusRepository
    )

    @Bean
    fun processIncomingMonobankTransactionsUseCase(
        monobankApi: MonobankApi,
        handleIncomingTransactionUseCase: HandleIncomingTransactionUseCase,
        categorizeExpenseUseCase: CategorizeExpenseUseCase,
        mergeSpendingsByDateUseCase: MergeSpendingsByDateUseCase,
        executeTransactionsUseCase: ExecuteTransactionsUseCase,
        handleOtherExpensesUseCase: HandleOtherExpensesUseCase,
    ) = ProcessIncomingMonobankTransactionsUseCase(
        monobankApi = monobankApi,
        handleIncomingTransactionUseCase = handleIncomingTransactionUseCase,
        categorizeExpenseUseCase = categorizeExpenseUseCase,
        executeTransactionsUseCase = executeTransactionsUseCase,
        mergeSpendingsByDateUseCase = mergeSpendingsByDateUseCase,
        handleOtherExpensesUseCase = handleOtherExpensesUseCase
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
        categorizationBot: CategorizationBot
    ) = HandleOtherExpensesUseCase(
        telegramBot = categorizationBot
    )
}
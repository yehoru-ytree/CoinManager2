package MailAggregator.MailAggregator.spreadsheet.usecases

import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.common.config.Config.Companion.TIME_ZONE
import MailAggregator.MailAggregator.common.usecases.CategorizeExpenseUseCase
import MailAggregator.MailAggregator.common.usecases.ExecuteTransactionsUseCase
import MailAggregator.MailAggregator.common.usecases.HandleOtherExpensesUseCase
import MailAggregator.MailAggregator.monobank.api.MonobankApi
import MailAggregator.MailAggregator.monobank.application.MonoTransaction
import jakarta.annotation.PostConstruct
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ProcessIncomingMonobankTransactionsUseCase(
    val monobankApi: MonobankApi,
    val handleIncomingTransactionUseCase: HandleIncomingTransactionUseCase,
    val categorizeExpenseUseCase: CategorizeExpenseUseCase,
    val mergeSpendingsByDateUseCase: MergeSpendingsByDateUseCase,
    val executeTransactionsUseCase: ExecuteTransactionsUseCase,
    val handleOtherExpensesUseCase: HandleOtherExpensesUseCase
) {
    companion object {
        const val ACCOUNT_ID = "5njU6znBYZ3Oxg0tQcB2og" //TODO remove from here
    }

    @PostConstruct
    operator fun invoke() {
        val to = Instant.now()
        val from = to.minusSeconds(7 * 24 * 3600)
        val today = LocalDate.now()

        val monoTransactions = monobankApi.getStatements(ACCOUNT_ID, from, to)
            .filter { it.raw.amount < 0 } //TODO somehow manage in future

        val newTransactions = handleIncomingTransactionUseCase(monoTransactions)

        val newTransactionsByDate = groupByLocalDate(newTransactions)

        val uncategorizedExpenses = mutableListOf<String>()

        for (day in newTransactionsByDate) {
            val categorizedExpenses = categorizeExpenseUseCase(day.value)

            uncategorizedExpenses.addAll(
                categorizedExpenses.filter { it.value == Category.OTHER }.keys
            )

            val mergedSpendings = mergeExpenses(categorizedExpenses.filterNot {
                it.value == Category.OTHER
            }, newTransactions)

            mergeSpendingsByDateUseCase(day.key, mergedSpendings)

            executeTransactionsUseCase(day.value)
        }

        handleOtherExpensesUseCase(
            monoTransactions.filter { it.id in uncategorizedExpenses }
        )

        val b = 5

    }

    fun mergeExpenses(
        categorizedExpenses: Map<String, Category>,   // txId -> category
        newTransactions: List<MonoTransaction>,
    ): Map<Category, Double> {
        return newTransactions
            .asSequence()
            .mapNotNull { tx ->
                val category = categorizedExpenses[tx.id] ?: return@mapNotNull null
                val amount = tx.raw.amount.toDouble() * -1 / 100.0
                category to amount
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, amounts) -> amounts.sum() }
    }

    fun groupByLocalDate(
        monoTransactions: List<MonoTransaction>,
        zoneId: ZoneId = TIME_ZONE,
    ): Map<LocalDate, List<MonoTransaction>> =
        monoTransactions.groupBy { tx ->
            Instant.ofEpochSecond(tx.raw.time)   // raw.time = Unix seconds
                .atZone(zoneId)
                .toLocalDate()
        }
}
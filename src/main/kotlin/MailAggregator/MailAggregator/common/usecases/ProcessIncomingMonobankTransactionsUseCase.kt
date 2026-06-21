package MailAggregator.MailAggregator.spreadsheet.usecases

import MailAggregator.MailAggregator.common.config.Config.Companion.TIME_ZONE
import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.common.usecases.CategorizeExpenseUseCase
import MailAggregator.MailAggregator.common.usecases.ExecuteTransactionsUseCase
import MailAggregator.MailAggregator.common.usecases.HandleOtherExpensesUseCase
import MailAggregator.MailAggregator.monobank.api.MonobankApi
import MailAggregator.MailAggregator.monobank.application.MonoTransaction
import MailAggregator.MailAggregator.telegram.CategorizationBot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class ProcessIncomingMonobankTransactionsUseCase(
    val monobankApi: MonobankApi,
    val handleNotProcessedTransactionsUseCase: HandleNotProcessedTransactionsUseCase,
    val categorizeExpenseUseCase: CategorizeExpenseUseCase,
    val mergeSpendingsByDateUseCase: MergeSpendingsByDateUseCase,
    val executeTransactionsUseCase: ExecuteTransactionsUseCase,
    val handleOtherExpensesUseCase: HandleOtherExpensesUseCase,
    val categoryRepository: CategoryRepository,
    val categorizationBot: CategorizationBot,
    val accountId: String,
    val statementWindowMinutes: Long,
) {
    operator fun invoke() {
        val to = Instant.now()
        val from = to.minusSeconds(statementWindowMinutes * 60)


        val monoTransactions = try {
            monobankApi.getStatements(accountId, from, to)
                .filter { it.raw.amount < 0 } //TODO somehow manage in future

        } catch (e: Exception) {
            println("Failed to fetch transactions from Monobank API: ${e.message}")
            return
        }

        val newTransactions = handleNotProcessedTransactionsUseCase(monoTransactions)

        val newTransactionsByDate = groupByLocalDate(newTransactions)

        val otherCategoryId = categoryRepository.findOther().id
        val uncategorizedExpenses = mutableListOf<String>()

        for (day in newTransactionsByDate) {
            val categorizedExpenses = categorizeExpenseUseCase(day.value)

            uncategorizedExpenses.addAll(
                categorizedExpenses.filter { it.value == otherCategoryId }.keys,
            )

            val mergedSpendings = mergeExpenses(
                categorizedExpenses.filterValues { it != otherCategoryId },
                newTransactions,
            )

            try {
                mergeSpendingsByDateUseCase(day.key, mergedSpendings)
            } catch (e: Exception) {
                println("Failed to update spreadsheet for date ${day.key}: ${e.message}")
                continue
            }

            sendAutoCategorizedLogs(
                day.value,
                categorizedExpenses.filterValues { it != otherCategoryId },
            )

            executeTransactionsUseCase(day.value)
        }

        handleOtherExpensesUseCase(
            monoTransactions.filter { it.id in uncategorizedExpenses },
        )
    }

    private fun sendAutoCategorizedLogs(
        transactions: List<MonoTransaction>,
        categorizedExpenses: Map<String, UUID>,
    ) {
        val txById = transactions.associateBy { it.id }
        categorizedExpenses.forEach { (txId, categoryId) ->
            val tx = txById[txId] ?: return@forEach
            val category = categoryRepository.findById(categoryId) ?: return@forEach
            try {
                categorizationBot.sendLog(tx, category)
            } catch (e: Exception) {
                println("Failed to send Telegram log for transaction $txId: ${e.message}")
            }
        }
    }

    fun mergeExpenses(
        categorizedExpenses: Map<String, UUID>,   // txId -> categoryId
        newTransactions: List<MonoTransaction>,
    ): Map<UUID, Double> =
        newTransactions
            .asSequence()
            .mapNotNull { tx ->
                val categoryId = categorizedExpenses[tx.id] ?: return@mapNotNull null
                val amount = tx.raw.amount.toDouble() * -1 / 100.0
                categoryId to amount
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, amounts) -> amounts.sum() }

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

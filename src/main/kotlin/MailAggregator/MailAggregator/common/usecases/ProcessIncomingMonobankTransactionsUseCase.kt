package MailAggregator.MailAggregator.spreadsheet.usecases

import MailAggregator.MailAggregator.common.config.Config.Companion.TIME_ZONE
import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.common.usecases.CategorizeExpenseUseCase
import MailAggregator.MailAggregator.common.usecases.ExecuteTransactionsUseCase
import MailAggregator.MailAggregator.common.usecases.HandleOtherExpensesUseCase
import MailAggregator.MailAggregator.household.Household
import MailAggregator.MailAggregator.household.MonobankAccount
import MailAggregator.MailAggregator.household.repository.HouseholdRepository
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
    val householdRepository: HouseholdRepository,
    val categorizationBot: CategorizationBot,
    val statementWindowMinutes: Long,
) {
    operator fun invoke() {
        val to = Instant.now()
        val from = to.minusSeconds(statementWindowMinutes * 60)

        for (account in householdRepository.findAllMonobankAccounts()) {
            try {
                processAccount(account, from, to)
            } catch (e: Exception) {
                println("Failed to process Monobank account ${account.accountId}: ${e.message}")
            }
        }
    }

    private fun processAccount(account: MonobankAccount, from: Instant, to: Instant) {
        val user = householdRepository.findUserById(account.userId) ?: return
        val household = householdRepository.findHousehold(user.householdId) ?: return

        val monoTransactions = monobankApi.getStatements(account.token, account.accountId, household.id, from, to)
            .filter { it.raw.amount < 0 } // TODO income flows in future

        val newTransactions = handleNotProcessedTransactionsUseCase(monoTransactions)
        if (newTransactions.isEmpty()) return

        val otherCategoryId = categoryRepository.findOther(household.id).id
        val uncategorizedExpenses = mutableListOf<String>()
        val newTransactionsByDate = groupByLocalDate(newTransactions)

        for (day in newTransactionsByDate) {
            val categorizedExpenses = categorizeExpenseUseCase(household.id, day.value)

            uncategorizedExpenses.addAll(
                categorizedExpenses.filter { it.value == otherCategoryId }.keys,
            )

            val mergedSpendings = mergeExpenses(
                categorizedExpenses.filterValues { it != otherCategoryId },
                newTransactions,
            )

            try {
                mergeSpendingsByDateUseCase(household, day.key, mergedSpendings)
            } catch (e: Exception) {
                println("Failed to update spreadsheet for date ${day.key}: ${e.message}")
                continue
            }

            sendAutoCategorizedLogs(
                household,
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
        household: Household,
        transactions: List<MonoTransaction>,
        categorizedExpenses: Map<String, UUID>,
    ) {
        val txById = transactions.associateBy { it.id }
        categorizedExpenses.forEach { (txId, categoryId) ->
            val tx = txById[txId] ?: return@forEach
            val category = categoryRepository.findById(categoryId) ?: return@forEach
            try {
                categorizationBot.sendLog(household, tx, category)
            } catch (e: Exception) {
                println("Failed to send Telegram log for transaction $txId: ${e.message}")
            }
        }
    }

    fun mergeExpenses(
        categorizedExpenses: Map<String, UUID>,
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
            Instant.ofEpochSecond(tx.raw.time)
                .atZone(zoneId)
                .toLocalDate()
        }
}

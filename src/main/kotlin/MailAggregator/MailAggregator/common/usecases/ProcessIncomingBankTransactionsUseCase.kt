package MailAggregator.MailAggregator.spreadsheet.usecases

import MailAggregator.MailAggregator.bank.BankAccount
import MailAggregator.MailAggregator.bank.BankApi
import MailAggregator.MailAggregator.bank.BankType
import MailAggregator.MailAggregator.bank.Transaction
import MailAggregator.MailAggregator.bank.repository.BankAccountRepository
import MailAggregator.MailAggregator.common.config.Config.Companion.TIME_ZONE
import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.common.usecases.CategorizeExpenseUseCase
import MailAggregator.MailAggregator.common.usecases.ExecuteTransactionsUseCase
import MailAggregator.MailAggregator.common.usecases.HandleOtherExpensesUseCase
import MailAggregator.MailAggregator.household.Household
import MailAggregator.MailAggregator.household.repository.HouseholdRepository
import MailAggregator.MailAggregator.telegram.CategorizationBot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Periodic job: walks all linked bank accounts, dispatches each to the matching [BankApi]
 * implementation based on `bankType`, and feeds new transactions through the existing
 * categorisation / sheet-merge / Telegram-broadcast pipeline.
 */
class ProcessIncomingBankTransactionsUseCase(
    val bankApis: Map<BankType, BankApi>,
    val handleNotProcessedTransactionsUseCase: HandleNotProcessedTransactionsUseCase,
    val categorizeExpenseUseCase: CategorizeExpenseUseCase,
    val mergeSpendingsByDateUseCase: MergeSpendingsByDateUseCase,
    val executeTransactionsUseCase: ExecuteTransactionsUseCase,
    val handleOtherExpensesUseCase: HandleOtherExpensesUseCase,
    val categoryRepository: CategoryRepository,
    val bankAccountRepository: BankAccountRepository,
    val householdRepository: HouseholdRepository,
    val categorizationBot: CategorizationBot,
    val statementWindowMinutes: Long,
) {
    operator fun invoke() {
        val to = Instant.now()
        val from = to.minusSeconds(statementWindowMinutes * 60)

        for (account in bankAccountRepository.findAll()) {
            try {
                processAccount(account, from, to)
            } catch (e: Exception) {
                println("Failed to process ${account.bankType} account ${account.accountId}: ${e.message}")
            }
        }
    }

    private fun processAccount(account: BankAccount, from: Instant, to: Instant) {
        // PrivatBank has no pull-based API for физлица — Privat accounts get their transactions
        // via PrivatEmailIngestor (push from Privat's email notifications). Skip silently so the
        // polling job doesn't spam logs every cycle.
        if (account.bankType == BankType.PRIVATBANK) return
        val api = bankApis[account.bankType] ?: run {
            println("No BankApi registered for ${account.bankType}; skipping account ${account.accountId}")
            return
        }
        val user = householdRepository.findUserById(account.userId) ?: return
        val household = householdRepository.findHousehold(user.householdId) ?: return

        val transactions = api.getStatements(account, household.id, from, to)
        processTransactionsForHousehold(household, transactions)
    }

    /**
     * Shared pipeline: dedup → categorise → merge into sheet → Telegram log → mark processed.
     * Used by the polling job (one batch per linked bank account) AND by the email-driven
     * Privat ingestor (one tx at a time as emails arrive).
     */
    fun processTransactionsForHousehold(household: Household, transactions: List<Transaction>) {
        val expenseTransactions = transactions.filter { it.amount < 0 } // TODO income flows in future

        val newTransactions = handleNotProcessedTransactionsUseCase(expenseTransactions)
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
            expenseTransactions.filter { it.id in uncategorizedExpenses },
        )
    }

    private fun sendAutoCategorizedLogs(
        household: Household,
        transactions: List<Transaction>,
        categorizedExpenses: Map<String, UUID>,
    ) {
        val txById = transactions.associateBy { it.id }
        categorizedExpenses.forEach { (txId, categoryId) ->
            val tx = txById[txId] ?: return@forEach
            val category = categoryRepository.findById(categoryId) ?: return@forEach
            try {
                categorizationBot.notifyHousehold(household, tx, category)
            } catch (e: Exception) {
                println("Failed to send Telegram log for transaction $txId: ${e.message}")
            }
        }
    }

    fun mergeExpenses(
        categorizedExpenses: Map<String, UUID>,
        newTransactions: List<Transaction>,
    ): Map<UUID, Double> =
        newTransactions
            .asSequence()
            .mapNotNull { tx ->
                val categoryId = categorizedExpenses[tx.id] ?: return@mapNotNull null
                val amount = tx.amount.toDouble() * -1 / 100.0
                categoryId to amount
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, amounts) -> amounts.sum() }

    fun groupByLocalDate(
        transactions: List<Transaction>,
        zoneId: ZoneId = TIME_ZONE,
    ): Map<LocalDate, List<Transaction>> =
        transactions.groupBy { tx ->
            Instant.ofEpochSecond(tx.time)
                .atZone(zoneId)
                .toLocalDate()
        }
}

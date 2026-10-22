package MailAggregator.MailAggregator.common.usecases

import MailAggregator.MailAggregator.bank.Transaction
import MailAggregator.MailAggregator.common.config.Config.Companion.TIME_ZONE
import MailAggregator.MailAggregator.household.repository.HouseholdRepository
import MailAggregator.MailAggregator.spreadsheet.usecases.MergeSpendingsByDateUseCase
import java.time.Instant
import java.util.UUID

/**
 * Reverses a transaction's merge into the Google Sheet: subtracts its amount from [categoryId] on
 * the transaction's own date, so the cell goes back to what it was before the categorisation.
 *
 * Only the sheet is touched. The caller is responsible for the transaction's status — in practice
 * [HandleOtherExpensesUseCase] runs right after and flips it to PENDING_APPROVAL, which also
 * clears the stored category link.
 */
class UndoCategorizationUseCase(
    private val mergeSpendingsByDateUseCase: MergeSpendingsByDateUseCase,
    private val householdRepository: HouseholdRepository,
) {
    operator fun invoke(transaction: Transaction, categoryId: UUID): Boolean {
        val household = householdRepository.findHousehold(transaction.householdId) ?: return false

        val date = Instant.ofEpochSecond(transaction.time)
            .atZone(TIME_ZONE)
            .toLocalDate()

        // Same expression as the forward merge in HandleTelegramResponseUseCase, negated: expenses
        // are stored negative in minor units, so the forward direction is `amount * -1 / 100`.
        mergeSpendingsByDateUseCase(
            household = household,
            date = date,
            newExpenses = mapOf(categoryId to transaction.amount.toDouble() / 100.0),
        )
        return true
    }
}

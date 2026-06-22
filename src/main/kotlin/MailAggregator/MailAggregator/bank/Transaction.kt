package MailAggregator.MailAggregator.bank

import java.util.UUID

/**
 * Bank-agnostic transaction. Each [BankApi] implementation flattens its native DTO into this
 * shape so downstream consumers (categorisation, sheet writing, Telegram logs) don't need to
 * know which bank produced it.
 *
 * Amounts are in minor units (kopecks for UAH, cents for USD) where negative = expense, positive
 * = income. `time` is Unix epoch seconds.
 *
 * Synthetic cash entries have id prefix [CASH_ID_PREFIX] — check via [isCash] when behaviour
 * needs to diverge between bank-pulled and manually-entered transactions.
 */
data class Transaction(
    val id: String,
    val householdId: UUID,
    val createdAt: Long,
    val description: String,
    val time: Long,
    val amount: Long,
    val currencyCode: Int,
    val comment: String?,
    val counterName: String?,
) {
    val isCash: Boolean get() = id.startsWith(CASH_ID_PREFIX)

    companion object {
        const val CASH_ID_PREFIX = "cash-"
    }
}

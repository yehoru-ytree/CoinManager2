package MailAggregator.MailAggregator.bank

import java.time.Instant
import java.util.UUID

/**
 * Bank-agnostic interface for fetching transactions. Each supported bank provides one
 * implementation under its own package (e.g. `monobank/`), identified by [bankType]. The
 * polling job looks up `BankApi` impls by `bankType` and dispatches each linked account to
 * the matching impl.
 */
interface BankApi {
    val bankType: BankType

    fun getStatements(
        token: String,
        accountId: String,
        householdId: UUID,
        from: Instant,
        to: Instant = Instant.now(),
    ): List<Transaction>
}

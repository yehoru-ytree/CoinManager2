package MailAggregator.MailAggregator.bank

import java.time.Instant
import java.util.UUID

/**
 * Bank-agnostic interface for fetching transactions. Each supported bank provides one
 * implementation under its own package (e.g. `monobank/`), identified by [bankType]. The
 * polling job looks up `BankApi` impls by `bankType` and dispatches each linked account to
 * the matching impl.
 *
 * Implementations get the full [BankAccount] so they can pick whichever credentials they need
 * (e.g. PrivatBank uses both `token` and `clientId`, Monobank only uses `token`).
 */
interface BankApi {
    val bankType: BankType

    fun getStatements(
        account: BankAccount,
        householdId: UUID,
        from: Instant,
        to: Instant = Instant.now(),
    ): List<Transaction>
}

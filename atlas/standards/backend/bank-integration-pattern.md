---
domain: backend
scope: repo-specific
repos_observed: 1
confidence: observed
last_scanned: 2026-07-24
evidence:
  - src/main/kotlin/MailAggregator/MailAggregator/bank/BankApi.kt
  - src/main/kotlin/MailAggregator/MailAggregator/bank/BankType.kt
  - src/main/kotlin/MailAggregator/MailAggregator/bank/BankAccount.kt
  - src/main/kotlin/MailAggregator/MailAggregator/monobank/api/MonobankApi.kt
why: confirmed
occurrences: 4
origin: repo-choice
---

## Bank integration pattern

Fetching transactions is abstracted behind `bank/BankApi`:

```kotlin
interface BankApi {
    val bankType: BankType
    fun getStatements(account: BankAccount, householdId: UUID, from: Instant, to: Instant = Instant.now()): List<Transaction>
}
```

**To add a new bank:**

1. Add an entry to the `BankType` enum (`bank/BankType.kt`). The enum value's
   `.name` is the discriminator persisted on `BankAccount.bankType`;
   `BankType.fromString` resolves it back (case-insensitive) and `error()`s on
   an unknown value.
2. Implement `BankApi` in the bank's **own package** (mirroring `monobank/`).
   The implementation receives the full `BankAccount`, so it may use whichever
   credentials it needs (`token`, `accountId`, `clientId`).
3. Register the implementation as a Spring bean. The `List<BankApi>` collection
   wiring (see manual-bean-wiring) picks it up and dispatches accounts of that
   `bankType` to it — no change to the polling use case.

**Do not** branch on `bankType` with `when` inside shared code to special-case a
bank; put bank-specific behaviour in that bank's `BankApi` implementation.
`getStatements` returns bank-agnostic `Transaction` values — mapping from the
bank's own DTOs happens inside the implementation's package (e.g.
`monobank/api/MonoStatementMapper`).

---
domain: backend
scope: repo-specific
repos_observed: 1
confidence: observed
last_scanned: 2026-07-26
evidence:
  - src/main/kotlin/MailAggregator/MailAggregator/bank/BankApi.kt
  - src/main/kotlin/MailAggregator/MailAggregator/bank/BankType.kt
  - src/main/kotlin/MailAggregator/MailAggregator/bank/BankAccount.kt
  - src/main/kotlin/MailAggregator/MailAggregator/monobank/api/MonobankApi.kt
  - src/main/kotlin/MailAggregator/MailAggregator/privatbank/email/PrivatEmailIngestor.kt
why: confirmed
occurrences: 5
origin: repo-choice
---

## Bank integration pattern

A `BankType` enum entry is the one thing **every** supported bank has — it is the
discriminator persisted on `BankAccount.bankType` and resolved back (case-insensitive)
by `BankType.fromString`, which `error()`s on an unknown value.

The enum currently defines `MONOBANK`, `PRIVATBANK`, and `WISE`. `WISE` is a
reserved placeholder for an upcoming integration — it has no `BankApi`
implementation or ingestor yet, so no account can be linked to it.

Beyond that, banks integrate in **one of two styles**, depending on how the bank
exposes transactions. Do not assume every bank implements `BankApi`.

### Style 1 — pull (polled REST): implement `BankApi`

Used when the bank offers an API we can poll (e.g. **Monobank**).

```kotlin
interface BankApi {
    val bankType: BankType
    fun getStatements(account: BankAccount, householdId: UUID, from: Instant, to: Instant = Instant.now()): List<Transaction>
}
```

- Implement `BankApi` in the bank's own package (mirroring `monobank/`). The impl
  receives the full `BankAccount`, so it uses whichever credentials it needs.
- Register the implementation as a Spring bean. `Config.processIncomingBankTransactionsUseCase`
  collects all `BankApi` beans as a `List<BankApi>` and keys them with
  `associateBy(BankApi::bankType)`; `ScheduledTasks` drives the poll on
  `monobank.poll-interval`. No change to the use case is needed for a new pull bank.

### Style 2 — push (inbound notifications): a dedicated ingestor

Used when the bank has no pollable API and instead pushes notifications we
receive out-of-band (e.g. **PrivatBank**, whose card notifications arrive as
forwarded emails). These banks have a `BankType` entry **but no `BankApi`
implementation**.

- Add a `@Component` ingestor (see `PrivatEmailIngestor`) that is `@Scheduled`
  on its own interval (`email.imap.poll-interval`), parses the inbound source
  (`PrivatEmailParser`), and feeds the results into the **same** pipeline via
  `ProcessIncomingBankTransactionsUseCase.processTransactionsForHousehold(...)`.
- The `BankType` entry (e.g. `PRIVATBANK`) is still used as the discriminator to
  look up the owning `BankAccount` (`findByTypeAndAccountId` / `findByTypeAndToken`).

### Common rules

- Put bank-specific behaviour inside that bank's package/ingestor — do **not**
  branch on `bankType` with `when` in shared code.
- Whichever style, transactions enter the pipeline as bank-agnostic `Transaction`
  values; mapping from the bank's own DTOs/emails happens inside that bank's
  package (e.g. `monobank/api/MonoStatementMapper`, `PrivatEmailParser`).

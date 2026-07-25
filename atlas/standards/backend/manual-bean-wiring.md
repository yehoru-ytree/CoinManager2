---
domain: backend
scope: repo-specific
repos_observed: 1
confidence: observed
last_scanned: 2026-07-24
evidence:
  - src/main/kotlin/MailAggregator/MailAggregator/common/config/Config.kt
  - src/main/kotlin/MailAggregator/MailAggregator/ScheduledTasks.kt
  - src/main/kotlin/MailAggregator/MailAggregator/bank/BankApi.kt
why: confirmed
occurrences: 3
origin: repo-choice
---

## Manual bean wiring

Domain **use cases** are ordinary constructor-only classes. They are **not**
annotated `@Component` / `@Service` and are **not** component-scanned — instead
they are constructed explicitly as `@Bean` factory methods in
`common/config/Config.kt`.

**When you add a use case or change its dependencies:**

- Add (or edit) its `@Bean` factory in `Config.kt`. Constructor parameters are
  supplied there, either from other beans or from `@Value("${...}")` config
  keys.
- Do not annotate the use case class itself for scanning; keep the wiring in
  one place.

**Collection injection.** Every `BankApi` implementation on the classpath is
injected as a `List<BankApi>` into `processIncomingBankTransactionsUseCase` and
turned into a lookup with `bankApis.associateBy(BankApi::bankType)`. A new
`BankApi` bean therefore joins the dispatch map automatically — no change to the
use case is needed, only that the implementation is a registered bean.

**Exceptions to the pattern:** repositories (`@Service`), the Telegram gateway,
and the `@Component` runners/`ScheduledTasks` are component-scanned as usual.
The manual-wiring rule is specifically about **use cases**.

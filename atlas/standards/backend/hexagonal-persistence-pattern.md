---
domain: backend
scope: repo-specific
repos_observed: 1
confidence: observed
last_scanned: 2026-07-24
evidence:
  - src/main/kotlin/MailAggregator/MailAggregator/bank/repository/BankAccountRepository.kt
  - src/main/kotlin/MailAggregator/MailAggregator/bank/repository/jpa/BankAccountJpaRepository.kt
  - src/main/kotlin/MailAggregator/MailAggregator/bank/repository/jpa/BankAccountJpaEntity.kt
  - src/main/kotlin/MailAggregator/MailAggregator/common/repository/CategoryRepository.kt
  - src/main/kotlin/MailAggregator/MailAggregator/household/repository/HouseholdRepository.kt
why: confirmed
occurrences: 4
origin: repo-choice
---

## Hexagonal persistence pattern

Every persisted domain type is reached through **two layers**:

1. **Domain repository** — a `@Service` class named `<Type>Repository`, living in
   `<module>/repository/`. It exposes domain-typed methods (`insert`,
   `findAll`, `findBy…`, `update`) and takes the JPA repository as a constructor
   dependency (`private val jpa: <Type>JpaRepository`).
2. **JPA repository** — an `interface <Type>JpaRepository : JpaRepository<<Type>JpaEntity, UUID>`
   under `<module>/repository/jpa/`, alongside the `<Type>JpaEntity`.

**Mapping** between the JPA entity and the domain model is done inside the
`@Service` repository with private extension helpers `toEntity()` and
`toDomain()`. The domain model type never carries JPA annotations; the entity
type never leaks past the repository.

**Rules for changes:**

- Use cases, controllers, and other services depend on the domain
  `@Service` repository — **never** inject a `JpaRepository` directly outside
  its `repository/jpa/` package.
- Enum-valued columns are stored as their `.name` string and rebuilt via the
  enum's `fromString` (see `BankAccountRepository` ↔ `BankType`).
- When you add a field to a domain model, thread it through **both** `toEntity()`
  and `toDomain()`; a field missing from either mapper is silently dropped on
  round-trip.

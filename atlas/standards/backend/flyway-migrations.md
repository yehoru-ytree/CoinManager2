---
domain: backend
scope: repo-specific
repos_observed: 1
confidence: observed
last_scanned: 2026-07-24
evidence:
  - src/main/resources/db/migration/V1__Init.sql
  - src/main/resources/db/migration/V5__MultiUserSchema.sql
  - src/main/resources/db/migration/V10__MonthCategoryLayout.sql
  - src/main/resources/application.properties
why: confirmed
occurrences: 3
origin: repo-choice
---

## Flyway migrations

Schema is owned entirely by Flyway. `application.properties` sets
`spring.flyway.enabled=true`, `spring.flyway.default-schema=bankaggregator`, and
`spring.jpa.hibernate.ddl-auto=none` — Hibernate never creates or alters
tables, so **every schema change is a migration**.

**Conventions:**

- Files live in `src/main/resources/db/migration/` named
  `V{n}__Description.sql`. `n` is monotonically increasing (currently through
  `V10`); `Description` is a short PascalCase-ish summary.
- Objects are created in the `bankaggregator` schema (matching
  `default-schema`). Cross-schema references should qualify with
  `bankaggregator.`.
- **Never edit a migration that has already been merged** — Flyway validates
  checksums and a changed file fails startup. To fix or extend, add the next
  `V{n+1}` migration.

**When a PR adds or changes a persisted field:** there must be a corresponding
new migration in the same PR. A JPA entity field with no backing column (and
`ddl-auto=none`) fails at runtime, not at compile time.

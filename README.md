# CoinManager2

A personal-finance aggregator for Ukrainian bank cards. It pulls card
transactions from **Monobank** (polled REST API) and **PrivatBank** (parsed from
forwarded notification emails), lets a household categorise each spend through a
**Telegram bot**, and writes the categorised totals into a per-household **Google
Sheet**.

## Stack

- **Kotlin 2.2** on **JVM 21**, **Spring Boot** (Web, JDBC, Data JPA).
- **PostgreSQL** with **Flyway** migrations; schema `bankaggregator`.
- **Gradle** (Kotlin DSL) — `build.gradle.kts`. A Maven `pom.xml` is present but the
  build of record is Gradle.
- Integrations: Monobank REST, Google Sheets API, Telegram Bot API (pengrad),
  IMAP (Angus Mail) for PrivatBank email ingestion.

## Architecture

The service is organised into domain modules under
`src/main/kotlin/MailAggregator/MailAggregator/`:

| Module | Responsibility |
|---|---|
| `bank/` | Bank-agnostic transaction model + the `BankApi` abstraction and `BankType` discriminator. |
| `monobank/` | `BankApi` implementation over the Monobank REST API. |
| `privatbank/` | PrivatBank support via IMAP email ingestion + parsing. |
| `common/` | Categories, month layout, and the expense-processing use cases. |
| `household/` | Multi-user households, bot users, invite tokens, bootstrap/backfill runners. |
| `spreadsheet/` | Google Sheets authentication and read/write use cases. |
| `telegram/` | Telegram gateway, update router, and the interactive wizards. |

**Persistence pattern.** Each persisted module exposes a domain-level
`@Service` repository (e.g. `bank/repository/BankAccountRepository`) that wraps a
Spring Data `JpaRepository` under `repository/jpa/` and maps between the JPA
entity and the domain type with private `toEntity()` / `toDomain()` helpers.

**Bean wiring.** Domain use cases are plain classes constructed as explicit
`@Bean` factory methods in `common/config/Config.kt` — they are not
component-scanned. Every `BankApi` implementation on the classpath is collected
into a `List<BankApi>` and keyed by `bankType` so the polling job can dispatch
each linked account to the matching bank.

**Ingestion.** `ScheduledTasks` polls Monobank on `monobank.poll-interval`
(`@Scheduled`, `@EnableScheduling` on `MailAggregatorApplication`);
`PrivatEmailIngestor` polls the configured IMAP mailbox on
`email.imap.poll-interval` and is inert while `email.imap.user` / `password` are
blank.

**Telegram.** `UpdateRouter` registers long-polling in a `@PostConstruct` and
dispatches each update through the `Wizard` beans. A wizard's
`requiresRegistration` flag gates it behind a linked household; `CreateHousehold`
overrides it to `false` so a fresh chat can bootstrap.

## Configuration

All runtime configuration comes from environment variables consumed in
`src/main/resources/application.properties` (see `.env.example` for the full
list). Notable keys: `POSTGRES_PASSWORD`, `MONOBANK_TOKEN` /
`MONOBANK_ACCOUNT_ID`, `GOOGLE_SHEET_ID`, `TEMPLATE_SPREADSHEET_ID` /
`TEMPLATE_SHEET_TITLE`, `TELEGRAM_BOT_TOKEN` / `TELEGRAM_OWNER_CHAT_ID`, and the
`EMAIL_IMAP_*` group.

## Build & run

```bash
# Tests + jar (Postgres started via the docker-compose Gradle plugin for bootRun)
./gradlew build

# Run locally (brings up the `db` service from docker-compose.yml first)
./gradlew bootRun
```

CI builds every push/PR via `.github/workflows/build.yml`, running the full test
suite against a Postgres service container that mirrors `docker-compose.yml`.

## Database migrations

Flyway migrations live in `src/main/resources/db/migration/` as
`V{n}__Description.sql`, applied in monotonic order into the `bankaggregator`
schema (`spring.jpa.hibernate.ddl-auto=none` — the schema is owned entirely by
Flyway). Never edit a migration that has already been merged; add a new
`V{n+1}` instead.

## Documentation conformance

Coding standards and architectural patterns are documented under
[`atlas/`](atlas/index.yml). Pull requests are reviewed for documentation
conformance by an automated routine — see `.github/workflows/doc-review.yml`.

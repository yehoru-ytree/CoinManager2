---
domain: global
scope: repo-specific
repos_observed: 1
confidence: observed
last_scanned: 2026-07-24
evidence:
  - build.gradle.kts
  - src/main/kotlin/MailAggregator/MailAggregator/MailAggregatorApplication.kt
  - src/main/kotlin/MailAggregator/MailAggregator/telegram/wizard/Wizard.kt
  - src/main/kotlin/MailAggregator/MailAggregator/bank/BankApi.kt
why: confirmed
occurrences: 4
origin: repo-choice
---

## Kotlin / Spring conventions

- **Toolchain:** Kotlin `2.2.0`, `java.sourceCompatibility = JavaVersion.VERSION_21`,
  `jvmTarget = JVM_21`. Spring Boot with the `plugin.spring` and `plugin.jpa`
  Kotlin compiler plugins.
- **Build of record:** Gradle Kotlin DSL (`build.gradle.kts`). A `pom.xml` /
  `mvnw` pair is committed but is not the build used by CI — do not add
  dependencies to Maven and expect them to take effect.
- **Compiler args:** `freeCompilerArgs = ["-Xjsr305=strict"]` — Java nullability
  annotations are enforced.
- **Package root:** every source file sits under `MailAggregator.MailAggregator`
  (a doubled segment). Keep new files under the same root; do not introduce a
  competing top-level package.
- **Comments:** KDoc block comments on interfaces, non-obvious lifecycle wiring,
  and subtle invariants are the established house style here (see `Wizard`,
  `BankApi`, `BootstrapHouseholdRunner`). This differs from some sister repos —
  keep explaining *why*, not restating *what*.
- **Time zones:** `main()` sets the JVM default to UTC; a single app-level
  `Config.TIME_ZONE` (from `app.timezone`) is the source of truth for
  presentation-time zone. Do not read `ZoneId.systemDefault()` ad hoc.

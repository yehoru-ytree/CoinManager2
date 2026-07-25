---
domain: backend
scope: repo-specific
repos_observed: 1
confidence: observed
last_scanned: 2026-07-24
evidence:
  - src/main/kotlin/MailAggregator/MailAggregator/telegram/wizard/Wizard.kt
  - src/main/kotlin/MailAggregator/MailAggregator/telegram/UpdateRouter.kt
  - src/main/kotlin/MailAggregator/MailAggregator/telegram/wizard/CreateHouseholdWizard.kt
  - src/main/kotlin/MailAggregator/MailAggregator/telegram/PengradTelegramGateway.kt
why: confirmed
occurrences: 4
origin: repo-choice
---

## Telegram wizard pattern

Interactive multi-step bot flows implement the `Wizard` interface
(`telegram/wizard/Wizard.kt`). Each wizard owns its own in-memory
`chatId -> State` map and is driven by `UpdateRouter`.

**Contract methods:** `hasState(chatId)`, `resetState(chatId)`,
`tryHandleMidFlow(context)`, `matchesStartTrigger(context)`, `start(context)`,
`tryHandleCallback(context)`. The `try*` methods return `true` to mean *"I
consumed this update — stop trying other handlers."*

**Registration gate.** `requiresRegistration` defaults to `true` — the wizard is
only offered to chats linked to a household. `CreateHouseholdWizard` overrides it
to `false` because it is how an unlinked chat bootstraps. `UpdateRouter` splits
the injected wizard list into public vs registered on this flag.

**Routing lifecycle (per text message):** mid-flow wizard first
(`tryHandleMidFlow`), otherwise the first wizard whose `matchesStartTrigger`
returns true is `start`ed after other wizards' state is reset. Callback queries
go through `tryHandleCallback` (each wizard checks its own callback-data prefix).

**Long-polling** is registered once in `UpdateRouter`'s `@PostConstruct`
(`gateway.start(::handleUpdate)`); the concrete transport is
`PengradTelegramGateway`. Do not start polling elsewhere.

**To add a wizard:** implement `Wizard`, register it as a bean so the router
picks it up, and set `requiresRegistration` appropriately. Contexts
(`MessageContext` / `CallbackContext`) are prepared by the router — read
`user` / `household` from there rather than re-fetching.

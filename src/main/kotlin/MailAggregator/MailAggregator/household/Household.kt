package MailAggregator.MailAggregator.household

import java.util.UUID

data class Household(
    val id: UUID,
    val name: String?,
    val sheetId: String,
    val templateSheetTitle: String,
)

data class BotUser(
    val id: UUID,
    val chatId: Long,
    val name: String?,
    val householdId: UUID,
)

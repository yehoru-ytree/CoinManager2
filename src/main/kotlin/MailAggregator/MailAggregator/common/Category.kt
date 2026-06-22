package MailAggregator.MailAggregator.common

import java.util.UUID

data class Category(
    val id: UUID,
    val householdId: UUID,
    val name: String,
    val displayName: String,
    val sheetRow: Int,
    val priority: Int,
    val keywords: List<String>,
    val isOther: Boolean,
)

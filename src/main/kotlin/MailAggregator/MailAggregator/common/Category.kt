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
    val isDefault: Boolean = false,
    val status: Status = Status.ACTIVE,
) {
    /**
     * Soft-delete via [Status.DELETED] keeps the row in the DB so historical transactions still
     * resolve their [Category.id] and past-month spending stays intact.
     */
    enum class Status {
        ACTIVE,
        DELETED,
    }
}

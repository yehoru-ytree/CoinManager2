package MailAggregator.MailAggregator.monobank.application

enum class TransactionStatus {
    EXECUTED,
    RECEIVED,
    IGNORED,
    PENDING_APPROVAL;

    companion object {
        fun fromString(value: String): TransactionStatus {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: error("Unknown TransactionStatus: $value")
        }
    }
}
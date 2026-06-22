package MailAggregator.MailAggregator.bank

enum class TransactionStatus {
    EXECUTED,
    RECEIVED,
    IGNORED,
    PENDING_APPROVAL,
    ;

    companion object {
        fun fromString(value: String): TransactionStatus =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: error("Unknown TransactionStatus: $value")
    }
}

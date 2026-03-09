package MailAggregator.MailAggregator.monobank.application

enum class TransactionStatus {
    EXECUTED,
    PENDING,
    IGNORED;

    companion object {
        fun fromString(value: String): TransactionStatus {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: error("Unknown TransactionStatus: $value")
        }
    }
}
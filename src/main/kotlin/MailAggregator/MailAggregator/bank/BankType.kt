package MailAggregator.MailAggregator.bank

/** Discriminator for [BankAccount.bankType] / [BankApi.bankType]. Add new entries when we support
 *  more banks. WISE is a reserved placeholder for an upcoming integration (no BankApi / ingestor yet). */
enum class BankType {
    MONOBANK,
    PRIVATBANK,
    WISE,
    ;

    companion object {
        fun fromString(value: String): BankType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: error("Unknown BankType: $value")
    }
}

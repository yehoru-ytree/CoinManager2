package MailAggregator.MailAggregator.bank

/** Discriminator for [BankAccount.bankType] / [BankApi.bankType]. Add new entries when we support
 *  more banks (e.g. PRIVATBANK). */
enum class BankType {
    MONOBANK,
    PRIVATBANK,
    ;

    companion object {
        fun fromString(value: String): BankType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: error("Unknown BankType: $value")
    }
}

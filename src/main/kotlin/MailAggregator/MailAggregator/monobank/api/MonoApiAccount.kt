package MailAggregator.MailAggregator.monobank.api

data class MonoApiAccount(
    val id: String,
    val type: String? = null,
    val currencyCode: Int,
    val iban: String? = null
)
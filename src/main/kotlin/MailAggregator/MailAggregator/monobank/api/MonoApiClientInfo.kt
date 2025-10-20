package MailAggregator.MailAggregator.monobank.api

data class MonoApiClientInfo(
    val name: String? = null,
    val clientId: String? = null,
    val accounts: List<MonoApiAccount> = emptyList()
)
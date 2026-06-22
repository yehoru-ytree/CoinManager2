package MailAggregator.MailAggregator.bank

import java.util.UUID

data class BankAccount(
    val id: UUID,
    val userId: UUID,
    val bankType: BankType,
    val token: String,
    val accountId: String,
    val clientId: String? = null,
)

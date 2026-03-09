package MailAggregator.MailAggregator.monobank.application

import MailAggregator.MailAggregator.monobank.api.MonoApiTransaction
import MailAggregator.MailAggregator.monobank.application.TransactionStatus
import java.util.UUID

data class MonoTransaction(
    val id: String,
    val createdAt: Long,
    val raw: MonoApiTransaction,
)
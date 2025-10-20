package MailAggregator.MailAggregator.monobank.application

import MailAggregator.MailAggregator.monobank.api.MonoApiTransaction
import java.util.UUID

data class MonoTransaction(
    val id: UUID,
    val createdAt: Long,
    val raw: MonoApiTransaction
)
package MailAggregator.MailAggregator.monobank.api

import MailAggregator.MailAggregator.monobank.application.MonoTransaction
import java.util.UUID

object MonoStatementMapper {
    fun fromApi(api: MonoApiTransaction): MonoTransaction = MonoTransaction(
        id = UUID.randomUUID(),
        createdAt = api.time,
        raw = api
    )
}
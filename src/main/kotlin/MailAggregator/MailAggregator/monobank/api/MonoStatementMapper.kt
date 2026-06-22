package MailAggregator.MailAggregator.monobank.api

import MailAggregator.MailAggregator.monobank.application.MonoTransaction
import java.util.UUID

object MonoStatementMapper {
    fun fromApi(api: MonoApiTransaction, householdId: UUID): MonoTransaction = MonoTransaction(
        id = api.id,
        householdId = householdId,
        createdAt = api.time,
        raw = api,
    )
}

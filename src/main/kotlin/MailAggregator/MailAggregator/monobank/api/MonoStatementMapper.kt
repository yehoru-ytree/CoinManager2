package MailAggregator.MailAggregator.monobank.api

import MailAggregator.MailAggregator.bank.Transaction
import java.util.UUID

object MonoStatementMapper {
    fun fromApi(api: MonoApiTransaction, householdId: UUID): Transaction = Transaction(
        id = api.id,
        householdId = householdId,
        createdAt = api.time,
        description = api.description,
        time = api.time,
        amount = api.amount,
        currencyCode = api.currencyCode,
        comment = api.comment,
        counterName = api.counterName,
    )
}

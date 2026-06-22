package MailAggregator.MailAggregator.privatbank.api

import MailAggregator.MailAggregator.bank.Transaction
import MailAggregator.MailAggregator.common.config.Config.Companion.TIME_ZONE
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToLong

/**
 * ACP statement row → bank-agnostic [Transaction]. ACP reports debits with `TRANTYPE="D"` and a
 * positive `SUM` in the account's currency; we normalise to the project-wide convention of
 * negative-amount-means-expense by flipping sign on debits.
 */
object PrivatStatementMapper {
    private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")

    fun fromApi(api: PrivatApiTransaction, householdId: UUID): Transaction {
        val epochSec = LocalDateTime.parse(api.dateTime, DATE_TIME_FORMAT)
            .atZone(TIME_ZONE)
            .toEpochSecond()
        val magnitude = (api.sum.replace(',', '.').toDouble() * 100.0).roundToLong()
        val signed = if (api.tranType.equals("D", ignoreCase = true)) -magnitude else magnitude
        return Transaction(
            id = api.ref,
            householdId = householdId,
            createdAt = epochSec,
            description = api.purpose?.trim().orEmpty(),
            time = epochSec,
            amount = signed,
            currencyCode = currencyToIso(api.currency),
            comment = null,
            counterName = api.counterName?.trim(),
        )
    }

    private fun currencyToIso(code: String): Int = when (code.uppercase()) {
        "UAH" -> 980
        "USD" -> 840
        "EUR" -> 978
        "GBP" -> 826
        else -> 0
    }
}

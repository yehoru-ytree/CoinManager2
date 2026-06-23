package MailAggregator.MailAggregator.privatbank.email

import MailAggregator.MailAggregator.bank.Transaction
import java.math.BigDecimal
import java.util.UUID

/**
 * Parses the compact one-line transaction notification that Privat24 sends to email subscribers.
 *
 * Sample bodies (one per line):
 * - `-70.5₴ Супермаркети та продукти. АТБ 5*37 14:18 Бал. 33.45₴`
 * - `+100₴ Зарахування переказу: від YEHOR US 5*37 14:07 Бал. 103.95₴`
 * - `-1₴ Переказ на свою картку. *3855 5*90 14:04 Бал. 6.75₴`
 *
 * Returns null for unparseable bodies, for self-transfers (income side of these is double-counting),
 * and for incoming transfers (positive amounts) — only outgoing expenses become tracked Transactions.
 */
object PrivatEmailParser {
    // (sign)(amount)₴ (description-blob) (our-card-5*XX) (HH:mm) Бал. (balance)₴
    private val TX_PATTERN = Regex(
        """([+-])(\d+(?:\.\d+)?)₴\s+(.+?)\s+(5\*\d{2})\s+(\d{2}:\d{2})\s+Бал\.\s+(\d+(?:\.\d+)?)₴""",
    )

    // Both directions of a same-owner card-to-card transfer — money doesn't leave the household
    // so we drop these to avoid double-counting against the budget.
    private val SELF_TRANSFER_MARKERS = listOf("Переказ на свою картку", "Зарахування переказу")

    fun parse(body: String, householdId: UUID, txTimeEpochSec: Long, transactionId: String): Transaction? {
        val match = TX_PATTERN.find(body) ?: return null
        val (sign, amount, desc, _card, _time, _balance) = match.destructured

        if (sign != "-") return null
        if (SELF_TRANSFER_MARKERS.any { it in desc }) return null

        val amountMinor = (amount.toBigDecimal() * BigDecimal("100")).toLong()
        val signed = -amountMinor

        return Transaction(
            id = transactionId,
            householdId = householdId,
            createdAt = txTimeEpochSec,
            description = desc.trim().trimEnd('.').trim(),
            time = txTimeEpochSec,
            amount = signed,
            currencyCode = 980, // ₴ = UAH
            comment = null,
            counterName = null,
        )
    }
}

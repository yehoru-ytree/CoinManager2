package MailAggregator.MailAggregator.common.usecases

import MailAggregator.MailAggregator.bank.Transaction
import MailAggregator.MailAggregator.bank.TransactionStatus
import MailAggregator.MailAggregator.bank.repository.TransactionRepository
import MailAggregator.MailAggregator.bank.repository.TransactionStatusRepository
import java.nio.ByteBuffer
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Records a manually entered cash expense as a synthetic transaction so it flows through the
 * same categorisation / Google Sheets / Telegram-log pipeline as bank-pulled transactions.
 *
 * The synthetic tx has id `cash-<random>` (to make it distinguishable from real bank tx ids), the
 * amount stored in minor units **with negative sign** (expenses are negative in this codebase),
 * description "Наличка", and `PENDING_APPROVAL` status so the existing OTHER-flow keyboard can
 * route it to a category.
 *
 * The random part is a UUID packed as 22-char base64url rather than its 36-char canonical form:
 * the id travels inside inline-keyboard `callback_data` (`c|<txId>|<categoryId>`), which Telegram
 * caps at 64 bytes and rejects the whole message for when exceeded.
 */
class AddCashTransactionUseCase(
    private val transactionRepository: TransactionRepository,
    private val transactionStatusRepository: TransactionStatusRepository,
) {
    fun add(householdId: UUID, amountMajor: Double): Transaction {
        val nowSec = Instant.now().epochSecond
        val tx = Transaction(
            id = "${Transaction.CASH_ID_PREFIX}${compactRandomId()}",
            householdId = householdId,
            createdAt = nowSec,
            description = "Наличка",
            time = nowSec,
            amount = (-amountMajor * 100).toLong(),
            currencyCode = 980,
            comment = null,
            counterName = null,
        )
        transactionRepository.save(listOf(tx))
        transactionStatusRepository.save(mapOf(tx.id to TransactionStatus.PENDING_APPROVAL))
        return tx
    }

    private fun compactRandomId(): String {
        val uuid = UUID.randomUUID()
        val bytes = ByteBuffer.allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

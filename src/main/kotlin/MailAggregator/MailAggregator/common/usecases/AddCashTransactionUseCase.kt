package MailAggregator.MailAggregator.common.usecases

import MailAggregator.MailAggregator.bank.Transaction
import MailAggregator.MailAggregator.bank.TransactionStatus
import MailAggregator.MailAggregator.bank.repository.TransactionRepository
import MailAggregator.MailAggregator.bank.repository.TransactionStatusRepository
import java.time.Instant
import java.util.UUID

/**
 * Records a manually entered cash expense as a synthetic transaction so it flows through the
 * same categorisation / Google Sheets / Telegram-log pipeline as bank-pulled transactions.
 *
 * The synthetic tx has id `cash-<uuid>` (to make it distinguishable from real bank tx ids), the
 * amount stored in minor units **with negative sign** (expenses are negative in this codebase),
 * description "Наличка", and `PENDING_APPROVAL` status so the existing OTHER-flow keyboard can
 * route it to a category.
 */
class AddCashTransactionUseCase(
    private val transactionRepository: TransactionRepository,
    private val transactionStatusRepository: TransactionStatusRepository,
) {
    fun add(householdId: UUID, amountMajor: Double): Transaction {
        val nowSec = Instant.now().epochSecond
        val tx = Transaction(
            id = "cash-${UUID.randomUUID()}",
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
}

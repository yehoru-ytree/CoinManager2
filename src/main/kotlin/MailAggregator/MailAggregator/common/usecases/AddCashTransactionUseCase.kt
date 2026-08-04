package MailAggregator.MailAggregator.common.usecases

import MailAggregator.MailAggregator.bank.Transaction
import MailAggregator.MailAggregator.bank.TransactionStatus
import MailAggregator.MailAggregator.bank.repository.TransactionStatusRepository
import MailAggregator.MailAggregator.bank.repository.jpa.TransactionJpaEntity
import MailAggregator.MailAggregator.bank.repository.jpa.TransactionJpaRepository
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
    private val transactionJpaRepository: TransactionJpaRepository,
    private val transactionStatusRepository: TransactionStatusRepository,
) {
    fun add(householdId: UUID, amountMajor: Double): Transaction {
        val nowSec = Instant.now().epochSecond
        val tx = Transaction(
            id = "${Transaction.CASH_ID_PREFIX}${UUID.randomUUID()}",
            householdId = householdId,
            createdAt = nowSec,
            description = "Наличка",
            time = nowSec,
            amount = (-amountMajor * 100).toLong(),
            currencyCode = 980,
            comment = null,
            counterName = null,
        )
        transactionJpaRepository.save(
            TransactionJpaEntity(
                id = tx.id,
                householdId = tx.householdId,
                createdAt = tx.createdAt,
                description = tx.description,
                time = tx.time,
                amount = tx.amount,
                currencyCode = tx.currencyCode,
                comment = tx.comment,
                counterName = tx.counterName,
            )
        )
        transactionStatusRepository.save(mapOf(tx.id to TransactionStatus.PENDING_APPROVAL))
        return tx
    }
}

package MailAggregator.MailAggregator.common.usecases

import MailAggregator.MailAggregator.bank.Transaction
import MailAggregator.MailAggregator.bank.TransactionStatus
import MailAggregator.MailAggregator.bank.repository.TransactionStatusRepository
import java.util.UUID

class ExecuteTransactionsUseCase(
    val transactionStatusRepository: TransactionStatusRepository,
) {
    /**
     * Mark [transactions] processed.
     *
     * [categorized] maps transaction id -> the category its amount was merged into; those rows are
     * stored with the link so the merge can be reversed later (reply «Удалить»). Transactions
     * absent from the map fell through to OTHER and are about to be flipped to PENDING_APPROVAL by
     * [HandleOtherExpensesUseCase] — nothing of theirs reached the sheet, so they get a bare
     * EXECUTED with no category.
     */
    operator fun invoke(transactions: List<Transaction>, categorized: Map<String, UUID> = emptyMap()) {
        val ids = transactions.map { it.id }
        val withCategory = ids.mapNotNull { id -> categorized[id]?.let { id to it } }.toMap()
        val withoutCategory = ids.filterNot { it in withCategory }

        if (withCategory.isNotEmpty()) transactionStatusRepository.saveExecuted(withCategory)
        if (withoutCategory.isNotEmpty()) {
            transactionStatusRepository.save(withoutCategory.associateWith { TransactionStatus.EXECUTED })
        }
    }
}

package MailAggregator.MailAggregator.common.controller

import MailAggregator.MailAggregator.bank.Transaction
import MailAggregator.MailAggregator.common.usecases.AddCashTransactionUseCase
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST surface for recording a manual cash expense. Runs the same synthetic-transaction flow the
 * Telegram cash wizard drives, exposed over HTTP so external tools can post spends directly without
 * going through the bot.
 */
@RestController
@RequestMapping("/api/cash")
class CashController(
    private val addCashTransactionUseCase: AddCashTransactionUseCase,
) {

    @PostMapping
    fun add(
        @RequestParam householdId: UUID,
        @RequestParam amount: Double,
    ): Transaction = addCashTransactionUseCase.add(householdId, amount)
}

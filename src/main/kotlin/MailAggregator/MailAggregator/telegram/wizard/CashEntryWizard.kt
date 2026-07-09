package MailAggregator.MailAggregator.telegram.wizard

import MailAggregator.MailAggregator.common.usecases.AddCashTransactionUseCase
import MailAggregator.MailAggregator.household.Household
import MailAggregator.MailAggregator.telegram.TelegramGateway
import MailAggregator.MailAggregator.telegram.model.CategorizationRequest
import com.pengrad.telegrambot.model.Message
import org.springframework.context.MessageSource
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class CashEntryWizard(
    private val gateway: TelegramGateway,
    private val addCashTransactionUseCase: AddCashTransactionUseCase,
    private val messageSource: MessageSource,
    private val zoneId: ZoneId,
    /** Called after a cash tx is persisted to broadcast the categorisation prompt to household members. */
    private val broadcastTx: (CategorizationRequest) -> Unit,
) : Wizard {

    private val states = ConcurrentHashMap<Long, State>()

    private val cashTrigger: String by lazy { translate("trigger.cash") }
    private val cancelTrigger: String by lazy { translate("trigger.cancel") }

    override fun hasState(chatId: Long): Boolean = states.containsKey(chatId)

    override fun resetState(chatId: Long): Boolean = states.remove(chatId) != null

    override fun tryHandleMidFlow(context: MessageContext): Boolean {
        val state = states[context.chatId] ?: return false
        val replyTo = context.replyTo ?: return false

        // Cancel: reply to any bot message with the cancel trigger.
        if (replyTo.from()?.isBot == true && context.text.trim().equals(cancelTrigger, ignoreCase = true)) {
            states.remove(context.chatId)
            reply(context.msg, translate("flow.cancelled"))
            return true
        }

        // Step: reply threaded under the current prompt.
        if (replyTo.messageId() == state.lastPromptMessageId) {
            val household = context.household ?: return true // consumed even if we can't proceed
            handleAmountStep(context.chatId, context.msg, context.text, household)
            return true
        }
        return false
    }

    override fun matchesStartTrigger(context: MessageContext): Boolean =
        context.user != null &&
            context.replyTo == null &&
            context.text.trim().equals(cashTrigger, ignoreCase = true)

    override fun start(context: MessageContext) {
        val promptId = reply(context.msg, translate("cash.start", cancelTrigger)) ?: return
        states[context.chatId] = State.AwaitingAmount(promptId)
    }

    override fun tryHandleCallback(context: CallbackContext): Boolean = false // no callbacks in this wizard

    private fun handleAmountStep(chatId: Long, msg: Message, rawText: String, household: Household) {
        val amount = parseAmount(rawText)
        if (amount == null) {
            val promptId = reply(msg, translate("cash.badAmount")) ?: return
            states[chatId] = State.AwaitingAmount(promptId)
            return
        }
        val tx = try {
            addCashTransactionUseCase.add(household.id, amount)
        } catch (e: Exception) {
            println("Failed to add cash transaction for chat $chatId: ${e.message}")
            states.remove(chatId)
            reply(msg, translate("cash.failed", e.message ?: ""))
            return
        }
        states.remove(chatId)
        // Reuse the categorisation prompt path — bot broadcasts the keyboard to all household
        // members; whoever taps a category triggers the standard merge-into-sheet + log pipeline.
        broadcastTx(
            CategorizationRequest(
                transactionId = tx.id,
                householdId = tx.householdId,
                amount = "%.2f ₴".format(amount),
                description = tx.description,
                transactionTime = Instant.ofEpochSecond(tx.time)
                    .atZone(zoneId)
                    .toLocalDateTime().toString(),
            ),
        )
    }

    private fun parseAmount(raw: String): Double? {
        val normalized = raw.trim().replace(',', '.')
        val value = normalized.toDoubleOrNull() ?: return null
        return value.takeIf { it > 0.0 }
    }

    private fun reply(msg: Message, text: String): Int? =
        gateway.send(msg.chat().id(), text, replyToMessageId = msg.messageId())?.toInt()

    private fun translate(code: String, vararg args: Any?): String {
        val stringArgs: Array<Any?> = Array(args.size) { args[it]?.toString() }
        return messageSource.getMessage(code, stringArgs, Locale.ROOT)
    }

    private sealed class State {
        abstract val lastPromptMessageId: Int
        data class AwaitingAmount(override val lastPromptMessageId: Int) : State()
    }
}

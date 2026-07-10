package MailAggregator.MailAggregator.telegram.wizard

import MailAggregator.MailAggregator.household.BotUser
import MailAggregator.MailAggregator.household.Household
import com.pengrad.telegrambot.model.CallbackQuery
import com.pengrad.telegrambot.model.Message

/**
 * A multi-step bot flow owning its own conversation state (an in-memory `chatId -> State` map).
 *
 * The router calls a wizard through three phases per incoming text message:
 *  1. [tryHandleMidFlow] — if the wizard has state for this chat, decide whether to advance or cancel.
 *  2. [matchesStartTrigger] — if no wizard is mid-flow, check whether this plain message starts *this* wizard.
 *  3. [start] — router calls this after resetting other wizards' state (see [resetState] / [hasState]).
 *
 * Callback queries are dispatched via [tryHandleCallback] (each wizard checks its own callback-data prefix).
 *
 * Returning `true` from `try*` methods means "I consumed this update — do not try any other handler."
 */
interface Wizard {
    /**
     * True if this wizard is only offered *after* the registration gate (i.e. requires a linked
     * household). CreateHousehold is the exception (it's how a fresh chat gets bootstrapped) — it
     * overrides this to `false`. Everything else defaults to registered-only.
     */
    val requiresRegistration: Boolean get() = true

    /** True if this wizard currently owns state for [chatId] (i.e. is mid-flow with that chat). */
    fun hasState(chatId: Long): Boolean

    /** Clear any state for [chatId]. Returns true if state existed. */
    fun resetState(chatId: Long): Boolean

    /** Handle a mid-flow text message (cancel / step). Return true iff consumed. */
    fun tryHandleMidFlow(context: MessageContext): Boolean

    /** True if the given plain (no reply-to) message text matches this wizard's start trigger. */
    fun matchesStartTrigger(context: MessageContext): Boolean

    /** Start the flow. Called by the router only after [matchesStartTrigger] returned true. */
    fun start(context: MessageContext)

    /** Handle a callback query (inline keyboard tap). Return true iff consumed. */
    fun tryHandleCallback(context: CallbackContext): Boolean
}

/** Router-prepared context for a text-message update. */
data class MessageContext(
    val chatId: Long,
    val msg: Message,
    val text: String,
    val replyTo: Message?,
    val user: BotUser?,
    val household: Household?,
)

/** Router-prepared context for a callback-query update. */
data class CallbackContext(
    val chatId: Long,
    val cq: CallbackQuery,
    val data: String,
    val user: BotUser,
)

package MailAggregator.MailAggregator.telegram.wizard

import MailAggregator.MailAggregator.bank.Transaction
import MailAggregator.MailAggregator.bank.TransactionStatus
import MailAggregator.MailAggregator.bank.repository.TransactionRepository
import MailAggregator.MailAggregator.bank.repository.TransactionStatusRepository
import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.common.usecases.CategorizeExpenseUseCase
import MailAggregator.MailAggregator.common.usecases.HandleOtherExpensesUseCase
import MailAggregator.MailAggregator.common.usecases.RemoveKeywordUseCase
import MailAggregator.MailAggregator.common.usecases.UndoCategorizationUseCase
import MailAggregator.MailAggregator.telegram.TelegramGateway
import MailAggregator.MailAggregator.telegram.repository.TelegramLogMessageRepository
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import org.springframework.context.MessageSource
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Undo a transaction's categorisation and ask for it again.
 *
 * Triggered by replying «Удалить» to any bot message tied to a transaction (the log, or the
 * original prompt). Three things happen, in order:
 *
 *  1. **Un-merge.** The amount is subtracted from the category it was booked into, so the Google
 *     Sheet cell returns to its pre-categorisation value. For transactions executed before V11 the
 *     category wasn't recorded, so the user is asked which one to subtract from (`rxo|`).
 *  2. **Fix the rule.** If the categorisation came from a keyword, leaving that keyword in place
 *     would send the next identical transaction to the same wrong category — so the user is shown
 *     which keyword matched and offered its removal (`rxk|`). A [CategorizeExpenseUseCase]
 *     SPECIAL_RULES match lives in code and can only be reported, not removed.
 *  3. **Re-ask.** The transaction is handed back to [HandleOtherExpensesUseCase] — the exact path
 *     an uncategorised transaction takes: PENDING_APPROVAL plus the standard category keyboard
 *     broadcast to the whole household. Picking a category there runs the normal decision flow,
 *     and binding the description to the new category stays the job of the existing «Сохранить».
 *
 * Callback prefixes:
 *   - `rxo|<txId>|<categoryId>` — which category to subtract from (pre-V11 transactions only).
 *   - `rxk|<txId>|<0|1>`        — keep (0) or remove (1) the keyword that caused the match.
 */
class RecategorizeWizard(
    private val gateway: TelegramGateway,
    private val transactionRepository: TransactionRepository,
    private val transactionStatusRepository: TransactionStatusRepository,
    private val telegramLogMessageRepository: TelegramLogMessageRepository,
    private val categoryRepository: CategoryRepository,
    private val categorizeExpenseUseCase: CategorizeExpenseUseCase,
    private val undoCategorizationUseCase: UndoCategorizationUseCase,
    private val removeKeywordUseCase: RemoveKeywordUseCase,
    private val handleOtherExpensesUseCase: HandleOtherExpensesUseCase,
    private val messageSource: MessageSource,
) : Wizard {

    private val states = ConcurrentHashMap<Long, State>()

    private val recategorizeTrigger: String by lazy { applyLocale("trigger.recategorize") }
    private val cancelTrigger: String by lazy { applyLocale("trigger.cancel") }

    override fun hasState(chatId: Long): Boolean = states.containsKey(chatId)

    override fun resetState(chatId: Long): Boolean = states.remove(chatId) != null

    override fun tryHandleMidFlow(context: MessageContext): Boolean {
        val state = states[context.chatId] ?: return false
        val replyTo = context.replyTo ?: return false

        if (replyTo.from()?.isBot == true && context.text.trim().equals(cancelTrigger, ignoreCase = true)) {
            states.remove(context.chatId)
            reply(context.msg, applyLocale("flow.cancelled"))
            return true
        }
        // Callback-driven like RemoveCategoryWizard: swallow stray text replies on our own prompt
        // so they don't fall through and get saved as a sheet comment.
        return replyTo.messageId() == state.promptMessageId
    }

    /**
     * Unlike every other wizard this one starts from a *reply*, not a plain message — the reply is
     * what identifies the transaction. The router offers `matchesStartTrigger` before its
     * comment-reply branch, so this wins over «any other reply is a comment».
     */
    override fun matchesStartTrigger(context: MessageContext): Boolean =
        context.user != null &&
            context.replyTo?.from()?.isBot == true &&
            context.text.trim().equals(recategorizeTrigger, ignoreCase = true)

    override fun start(context: MessageContext) {
        val replyTo = context.replyTo ?: return
        val chatId = context.chatId

        val record = telegramLogMessageRepository.findByChatAndMessage(chatId, replyTo.messageId().toLong())
        if (record == null) {
            reply(context.msg, applyLocale("recategorize.txMissing"))
            return
        }
        val tx = transactionRepository.get(record.transactionId).orElse(null)
        if (tx == null) {
            reply(context.msg, applyLocale("recategorize.txDbMissing"))
            return
        }

        when (transactionStatusRepository.findByTransactionId(tx.id)) {
            TransactionStatus.EXECUTED -> startUndo(chatId, context.msg, tx)

            // Nothing ever reached the sheet, so there's nothing to subtract — just ask again.
            TransactionStatus.IGNORED -> {
                reply(context.msg, applyLocale("recategorize.wasIgnored"))
                askAgain(chatId, tx)
            }

            // PENDING_APPROVAL / RECEIVED / unknown: the live keyboard is still the way to decide.
            else -> reply(context.msg, applyLocale("recategorize.notCategorizedYet"))
        }
    }

    override fun tryHandleCallback(context: CallbackContext): Boolean {
        val parts = context.data.split('|')
        return when (parts.firstOrNull()) {
            OLD_CATEGORY_PREFIX -> { handleOldCategoryPick(context, parts); true }
            KEYWORD_PREFIX -> { handleKeywordDecision(context, parts); true }
            else -> false
        }
    }

    // ----- Step 1: un-merge -----

    private fun startUndo(chatId: Long, msg: Message, tx: Transaction) {
        val categoryId = transactionStatusRepository.findCategoryId(tx.id)
        if (categoryId != null) {
            undoAndOfferKeywordRemoval(chatId, tx, categoryId, replyTo = msg)
            return
        }
        // Pre-V11 row: the link was never recorded, so only the user knows where it went.
        val categories = activeCategories(tx.householdId)
        if (categories.isEmpty()) {
            reply(msg, applyLocale("recategorize.noCategories"))
            return
        }
        val promptId = gateway.send(
            chatId,
            applyLocale("recategorize.pickOld", formatAmount(tx)),
            keyboard = categoryKeyboard(tx.id, categories),
            replyToMessageId = msg.messageId(),
        )?.toInt() ?: return
        states[chatId] = State(transactionId = tx.id, promptMessageId = promptId)
    }

    private fun handleOldCategoryPick(context: CallbackContext, parts: List<String>) {
        if (parts.size != 3) {
            gateway.answerCallback(context.cq.id(), applyLocale("callback.badCallback"))
            return
        }
        val tx = transactionRepository.get(parts[1]).orElse(null)
        val categoryId = runCatching { UUID.fromString(parts[2]) }.getOrNull()
        val category = categoryId?.let { categoryRepository.findById(it) }
        if (tx == null ||
            category == null ||
            category.householdId != context.user.householdId ||
            tx.householdId != context.user.householdId
        ) {
            gateway.answerCallback(context.cq.id(), applyLocale("callback.badCallback"))
            return
        }
        // Another member may have already undone it while this keyboard sat idle.
        if (transactionStatusRepository.findByTransactionId(tx.id) != TransactionStatus.EXECUTED) {
            clearKeyboard(context.chatId, context.cq.message())
            gateway.answerCallback(context.cq.id(), applyLocale("callback.recategorizeStale"))
            return
        }

        clearKeyboard(context.chatId, context.cq.message())
        gateway.answerCallback(context.cq.id(), applyLocale("callback.saved"))
        states.remove(context.chatId)
        undoAndOfferKeywordRemoval(context.chatId, tx, category.id, replyTo = null)
    }

    private fun undoAndOfferKeywordRemoval(chatId: Long, tx: Transaction, categoryId: UUID, replyTo: Message?) {
        val category = categoryRepository.findById(categoryId)
        val undone = try {
            undoCategorizationUseCase(tx, categoryId)
        } catch (e: Exception) {
            // A Sheets failure must not leave the transaction half-undone: stop before the status
            // is reset, so the amount in the sheet and the stored category still agree.
            println("Failed to undo categorisation of ${tx.id}: ${e.message}")
            send(chatId, applyLocale("recategorize.undoFailed", e.message), replyTo)
            return
        }
        if (!undone) {
            // Household vanished between the log being sent and now — nothing was subtracted.
            send(chatId, applyLocale("recategorize.undoFailed", applyLocale("recategorize.householdMissing")), replyTo)
            return
        }
        send(
            chatId,
            applyLocale("recategorize.undone", formatAmount(tx), category?.displayName.orEmpty()),
            replyTo,
        )

        // Reset *before* anything else can fail. While the status still said EXECUTED with a
        // category attached, a second «Удалить» — after a bot restart, or simply because the
        // keyword question went unanswered — would subtract the same amount a second time.
        transactionStatusRepository.save(mapOf(tx.id to TransactionStatus.PENDING_APPROVAL))
        askAgain(chatId, tx)

        // The keyword question is independent of the new category, so it can safely come last:
        // losing this prompt (or its in-memory state) no longer strands the transaction.
        offerKeywordRemoval(chatId, tx, categoryId)
    }

    // ----- Step 2: fix the rule that caused the match -----

    private fun offerKeywordRemoval(chatId: Long, tx: Transaction, categoryId: UUID) {
        val explanation = try {
            categorizeExpenseUseCase.explain(tx.householdId, tx)
        } catch (e: Exception) {
            println("Failed to explain categorisation of ${tx.id}: ${e.message}")
            null
        }

        // Only offer removal when the rule points at the very category we just subtracted from.
        // Any other match would be about a categorisation that never happened.
        if (explanation == null || explanation.category.id != categoryId) {
            if (explanation == null) send(chatId, applyLocale("recategorize.keyword.none"), null)
            return
        }

        if (explanation.fromCode) {
            send(
                chatId,
                applyLocale("recategorize.keyword.inCode", displayKeyword(explanation.keyword), explanation.category.displayName),
                null,
            )
            return
        }

        val promptId = gateway.send(
            chatId,
            applyLocale(
                "recategorize.keyword.confirm",
                displayKeyword(explanation.keyword),
                explanation.category.displayName,
            ),
            keyboard = InlineKeyboardMarkup(
                arrayOf(
                    InlineKeyboardButton(applyLocale("recategorize.keyword.button.remove"))
                        .callbackData("$KEYWORD_PREFIX|${tx.id}|1"),
                    InlineKeyboardButton(applyLocale("recategorize.keyword.button.keep"))
                        .callbackData("$KEYWORD_PREFIX|${tx.id}|0"),
                ),
            ),
        )?.toInt() ?: return
        states[chatId] = State(
            transactionId = tx.id,
            promptMessageId = promptId,
            keywordCategoryId = categoryId,
            keyword = explanation.keyword,
        )
    }

    private fun handleKeywordDecision(context: CallbackContext, parts: List<String>) {
        val state = states[context.chatId]
        if (parts.size != 3 || state?.keyword == null || state.keywordCategoryId == null || state.transactionId != parts[1]) {
            gateway.answerCallback(context.cq.id(), applyLocale("callback.recategorizeStale"))
            return
        }
        clearKeyboard(context.chatId, context.cq.message())
        states.remove(context.chatId)

        if (parts[2] == "1") {
            val text = when (val result = removeKeywordUseCase(state.keywordCategoryId, state.keyword)) {
                is RemoveKeywordUseCase.Result.Removed ->
                    applyLocale("recategorize.keyword.removed", displayKeyword(result.keyword), result.category.displayName)
                is RemoveKeywordUseCase.Result.NotPresent ->
                    applyLocale("recategorize.keyword.notPresent", displayKeyword(result.keyword), result.category.displayName)
                RemoveKeywordUseCase.Result.CategoryNotFound ->
                    applyLocale("recategorize.keyword.categoryNotFound")
            }
            gateway.answerCallback(context.cq.id(), applyLocale("callback.saved"))
            send(context.chatId, text, null)
        } else {
            val category = categoryRepository.findById(state.keywordCategoryId)
            gateway.answerCallback(context.cq.id(), applyLocale("callback.kept"))
            send(
                context.chatId,
                applyLocale("recategorize.keyword.kept", displayKeyword(state.keyword), category?.displayName.orEmpty()),
                null,
            )
        }
    }

    // ----- Step 3: hand back to the ordinary uncategorised-transaction flow -----

    /** Hands the transaction to the ordinary uncategorised path: PENDING_APPROVAL + the standard
     *  category keyboard broadcast to every household member. */
    private fun askAgain(chatId: Long, tx: Transaction) {
        states.remove(chatId)
        try {
            handleOtherExpensesUseCase(listOf(tx))
        } catch (e: Exception) {
            println("Failed to re-prompt for transaction ${tx.id}: ${e.message}")
            send(chatId, applyLocale("recategorize.promptFailed"), null)
        }
    }

    // ----- helpers -----

    private fun activeCategories(householdId: UUID): List<Category> =
        categoryRepository.findAll(householdId).sortedBy { it.sheetRow }

    private fun categoryKeyboard(transactionId: String, categories: List<Category>): InlineKeyboardMarkup {
        val rows = categories.chunked(3).map { chunk ->
            chunk.map { cat ->
                InlineKeyboardButton(cat.displayName).callbackData("$OLD_CATEGORY_PREFIX|$transactionId|${cat.id}")
            }.toTypedArray()
        }
        return InlineKeyboardMarkup(*rows.toTypedArray())
    }

    private fun clearKeyboard(chatId: Long, message: Message?) {
        val messageId = message?.messageId()?.toLong() ?: return
        try {
            gateway.editKeyboard(chatId, messageId)
        } catch (e: Exception) {
            // «message is not modified» / «message to edit not found» — both harmless here.
            println("Could not clear keyboard on chat=$chatId msg=$messageId: ${e.message}")
        }
    }

    /** Expenses are negative minor units; show them the way the transaction log does. */
    private fun formatAmount(tx: Transaction): String = "%.2f".format(-tx.amount.toDouble() / 100.0)

    /**
     * Keywords added through «Сохранить» are stored `Regex.escape`d (`\Qcoffee\E`). Seeded and
     * hand-written ones are raw regex. Strip the quoting for display so the user sees the words
     * they typed rather than regex plumbing.
     */
    private fun displayKeyword(stored: String): String =
        if (stored.startsWith("\\Q") && stored.endsWith("\\E")) {
            stored.removePrefix("\\Q").removeSuffix("\\E")
        } else {
            stored
        }

    private fun send(chatId: Long, text: String, replyTo: Message?): Long? =
        gateway.send(chatId, text, replyToMessageId = replyTo?.messageId())

    private fun reply(msg: Message, text: String): Long? =
        gateway.send(msg.chat().id(), text, replyToMessageId = msg.messageId())

    private fun applyLocale(code: String, vararg args: Any?): String {
        val stringArgs: Array<Any?> = Array(args.size) { args[it]?.toString() }
        return messageSource.getMessage(code, stringArgs, Locale.ROOT)
    }

    /**
     * In-flight state for one chat. [keyword] / [keywordCategoryId] are set only while the
     * keyword-removal confirmation is on screen — the keyword itself is too long for Telegram's
     * 64-byte callback payload, so it's held here instead.
     */
    private data class State(
        val transactionId: String,
        val promptMessageId: Int,
        val keywordCategoryId: UUID? = null,
        val keyword: String? = null,
    )

    private companion object {
        const val OLD_CATEGORY_PREFIX = "rxo"
        const val KEYWORD_PREFIX = "rxk"
    }
}

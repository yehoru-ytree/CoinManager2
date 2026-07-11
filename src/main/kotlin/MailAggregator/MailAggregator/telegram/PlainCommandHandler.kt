package MailAggregator.MailAggregator.telegram

import MailAggregator.MailAggregator.bank.Transaction
import MailAggregator.MailAggregator.bank.TransactionStatus
import MailAggregator.MailAggregator.bank.repository.TransactionRepository
import MailAggregator.MailAggregator.bank.repository.TransactionStatusRepository
import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.common.usecases.HandleTelegramCommentUseCase
import MailAggregator.MailAggregator.common.usecases.SaveKeywordUseCase
import MailAggregator.MailAggregator.household.BotUser
import MailAggregator.MailAggregator.household.Household
import MailAggregator.MailAggregator.household.repository.HouseholdRepository
import MailAggregator.MailAggregator.household.repository.InviteTokenRepository
import MailAggregator.MailAggregator.household.usecase.JoinHouseholdUseCase
import MailAggregator.MailAggregator.telegram.repository.TelegramLogMessageRepository
import com.pengrad.telegrambot.model.CallbackQuery
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import org.springframework.context.MessageSource
import java.util.Locale
import java.util.UUID

/**
 * All stateless bot actions: help / invite / join / comment reply / save-keyword prompt +
 * the two transaction callback paths (`c|` and `k|`).
 *
 * Unlike [wizard.Wizard]s, this handler owns no per-chat state — every call is a one-shot action.
 * Split off from [CategorizationBot] so the bot itself is reduced to the broadcast API
 * ([CategorizationBot.promptHousehold] / [CategorizationBot.notifyHousehold]) and the router.
 */
class PlainCommandHandler(
    private val gateway: TelegramGateway,
    private val transactionRepository: TransactionRepository,
    private val transactionStatusRepository: TransactionStatusRepository,
    private val telegramLogMessageRepository: TelegramLogMessageRepository,
    private val handleTelegramCommentUseCase: HandleTelegramCommentUseCase,
    private val saveKeywordUseCase: SaveKeywordUseCase,
    private val categoryRepository: CategoryRepository,
    private val householdRepository: HouseholdRepository,
    private val inviteTokenRepository: InviteTokenRepository,
    private val joinHouseholdUseCase: JoinHouseholdUseCase,
    private val messageSource: MessageSource,
    private val onDecision: (String, CategorizationBot.Decision) -> Unit,
    /** Reused broadcast: category picked -> per-user log fan-out. Wired to CategorizationBot.notifyHousehold. */
    private val notifyHousehold: (Household, Transaction, Category?) -> Unit,
) {

    private val saveKeywordTrigger: String by lazy { applyLocale("trigger.save") }
    private val addCategoryTrigger: String by lazy { applyLocale("trigger.addCategory") }
    private val removeCategoryTrigger: String by lazy { applyLocale("trigger.removeCategory") }
    private val createHouseholdTrigger: String by lazy { applyLocale("trigger.createHousehold") }
    private val addCardTrigger: String by lazy { applyLocale("trigger.addCard") }
    private val cashTrigger: String by lazy { applyLocale("trigger.cash") }
    private val inviteTrigger: String by lazy { applyLocale("trigger.invite") }
    private val joinTrigger: String by lazy { applyLocale("trigger.join") }
    private val cancelTrigger: String by lazy { applyLocale("trigger.cancel") }
    private val helpTriggers: Set<String> by lazy {
        applyLocale("trigger.help").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    // ----- Message-side entry points (called by the router in specific phases) -----

    /** Send the /start greeting. */
    fun sendStartGreeting(chatId: Long) {
        gateway.send(chatId, applyLocale("bot.start.greeting", chatId))
    }

    /** True iff [text] starts with the join trigger (used by router before the registration gate). */
    fun matchesJoinTrigger(text: String, replyTo: Message?): Boolean =
        replyTo == null && text.trim().startsWith(joinTrigger, ignoreCase = true)

    fun handleJoinCommand(chatId: Long, msg: Message, text: String) {
        val token = text.trim().removePrefix(joinTrigger).trim()
        if (token.isEmpty()) {
            reply(msg, applyLocale("join.usage", joinTrigger))
            return
        }
        val result = joinHouseholdUseCase.join(chatId, token)
        when (result) {
            is JoinHouseholdUseCase.Result.Joined -> reply(msg, applyLocale("join.success", addCardTrigger))
            JoinHouseholdUseCase.Result.AlreadyInHousehold -> reply(msg, applyLocale("join.alreadyJoined"))
            JoinHouseholdUseCase.Result.InvalidToken -> reply(msg, applyLocale("join.invalidToken"))
        }
    }

    /** For unregistered chats: help if it matches, else the notRegistered directive. Consumed either way. */
    fun handleUnregisteredMessage(msg: Message, text: String, replyTo: Message?) {
        if (replyTo == null && text.trim().lowercase() in helpTriggers) {
            sendHelp(msg)
            return
        }
        if (replyTo == null) {
            reply(msg, applyLocale("bot.notRegistered", createHouseholdTrigger, joinTrigger))
        }
    }

    fun matchesInviteTrigger(text: String, replyTo: Message?): Boolean =
        replyTo == null && text.trim().equals(inviteTrigger, ignoreCase = true)

    fun handleInviteCommand(msg: Message, user: BotUser) {
        val token = inviteTokenRepository.create(user.householdId)
        reply(msg, applyLocale("invite.result", token, joinTrigger))
    }

    fun matchesHelpTrigger(text: String, replyTo: Message?): Boolean =
        replyTo == null && text.trim().lowercase() in helpTriggers

    fun sendHelp(msg: Message) {
        reply(
            msg,
            applyLocale(
                "help",
                createHouseholdTrigger,
                joinTrigger,
                addCardTrigger,
                inviteTrigger,
                addCategoryTrigger,
                removeCategoryTrigger,
                cashTrigger,
                saveKeywordTrigger,
                cancelTrigger,
            ),
        )
    }

    fun sendUnknown(msg: Message) {
        reply(msg, applyLocale("bot.unknown"))
    }

    /** True iff [msg] is a reply to a message posted by the bot. */
    fun isReplyToBotMessage(replyTo: Message?): Boolean =
        replyTo != null && replyTo.from()?.isBot == true

    fun handleCommentReply(msg: Message, replyToMessageId: Long, text: String) {
        if (text.isBlank()) return

        if (text.trim().equals(saveKeywordTrigger, ignoreCase = true)) {
            promptSaveKeywordCategory(msg.chat().id(), replyToMessageId)
            return
        }

        val saved = try {
            handleTelegramCommentUseCase(msg.chat().id(), replyToMessageId, text)
        } catch (e: Exception) {
            println("Failed to save Telegram comment: ${e.message}")
            reply(msg, applyLocale("comment.saveError"))
            return
        }
        // Thread the ack under the user's actual comment so the chat keeps a clean
        // «commented → ✓ saved» visual chain.
        reply(msg, if (saved) applyLocale("comment.saved") else applyLocale("comment.notFound"))
    }

    // ----- Callback-side entry point (c|... and k|...) -----

    /** Returns true if [data] was recognised as a categorisation or save-keyword callback. */
    fun tryHandleCallback(cq: CallbackQuery, chatId: Long, user: BotUser, data: String): Boolean =
        when (data.firstOrNull()) {
            'c' -> { handleCategorizationCallback(cq, chatId, user, data); true }
            'k' -> { handleSaveKeywordCallback(cq, chatId, user, data); true }
            else -> false
        }

    private fun handleCategorizationCallback(cq: CallbackQuery, chatId: Long, user: BotUser, data: String) {
        val parsed = parseCallbackData(user.householdId, data) ?: run {
            gateway.answerCallback(cq.id(), applyLocale("callback.badCallback"))
            return
        }

        // First-wins: if another household member already decided on this transaction, this tap
        // is a no-op — the decision is final. Strip this user's stale keyboard so they can't tap
        // again and answer the callback with a friendly toast.
        val existingStatus = transactionStatusRepository.findByTransactionId(parsed.txId)
        if (existingStatus == TransactionStatus.EXECUTED || existingStatus == TransactionStatus.IGNORED) {
            val message = cq.message()
            if (message != null) {
                gateway.editKeyboard(chatId, message.messageId().toLong())
            }
            gateway.answerCallback(cq.id(), applyLocale("callback.alreadyDone"))
            return
        }

        onDecision(parsed.txId, parsed.decision)

        // Remove keyboards from ALL household members' copies of this prompt, not just the one
        // who tapped. Otherwise other members still see a live keyboard pointing at a transaction
        // that's already been merged into the sheet → tapping it would double-count.
        clearKeyboardsForTx(parsed.txId)

        gateway.answerCallback(cq.id(), applyLocale("callback.saved"))
        notifyDecision(parsed.txId, parsed.decision)
    }

    private fun handleSaveKeywordCallback(cq: CallbackQuery, chatId: Long, user: BotUser, data: String) {
        val parsed = parseSaveKeywordCallback(user.householdId, data) ?: run {
            gateway.answerCallback(cq.id(), applyLocale("callback.badCallback"))
            return
        }
        val tx = transactionRepository.get(parsed.txId).orElse(null)
        if (tx == null) {
            gateway.answerCallback(cq.id(), applyLocale("callback.txNotFound"))
            return
        }
        val result = saveKeywordUseCase(parsed.categoryId, tx.description)
        val message = cq.message()
        if (message != null) {
            gateway.editKeyboard(chatId, message.messageId().toLong())
        }
        val (callbackText, replyText) = when (result) {
            is SaveKeywordUseCase.Result.Saved ->
                applyLocale("callback.saved") to applyLocale("savekw.success", result.keyword, result.category.displayName)
            is SaveKeywordUseCase.Result.AlreadyPresent ->
                applyLocale("callback.alreadyPresent") to applyLocale("savekw.alreadyPresent", result.keyword, result.category.displayName)
            SaveKeywordUseCase.Result.CategoryNotFound ->
                applyLocale("callback.categoryNotFound") to applyLocale("savekw.categoryNotFound")
            SaveKeywordUseCase.Result.EmptyKeyword ->
                applyLocale("callback.empty") to applyLocale("savekw.empty")
        }
        gateway.answerCallback(cq.id(), callbackText)
        gateway.send(chatId, replyText)
    }

    private fun promptSaveKeywordCategory(chatId: Long, replyToMessageId: Long) {
        val record = telegramLogMessageRepository.findByChatAndMessage(chatId, replyToMessageId)
        if (record == null) {
            gateway.send(chatId, applyLocale("savekw.txMissing"))
            return
        }
        val tx = transactionRepository.get(record.transactionId).orElse(null)
        if (tx == null) {
            gateway.send(chatId, applyLocale("savekw.txDbMissing"))
            return
        }
        if (tx.isCash) {
            // Cash entries all share description «Наличка» — saving it as a keyword would
            // pollute the category's regex list without ever matching anything useful (cash flow
            // bypasses CategorizeExpenseUseCase entirely).
            gateway.send(chatId, applyLocale("savekw.cashRejected"))
            return
        }
        val description = tx.description.trim()
        if (description.isEmpty()) {
            gateway.send(chatId, applyLocale("savekw.emptyDescription"))
            return
        }
        gateway.send(
            chatId,
            applyLocale("savekw.choose", description),
            keyboard = buildSaveKeywordKeyboard(tx.householdId, record.transactionId),
        )
    }

    private fun clearKeyboardsForTx(txId: String) {
        val prompts = telegramLogMessageRepository.findAllByTransactionId(txId)
        for (prompt in prompts) {
            try {
                gateway.editKeyboard(prompt.chatId, prompt.messageId)
            } catch (e: Exception) {
                // Telegram returns 400 «message is not modified» when the keyboard is already gone,
                // or «message to edit not found» if the user deleted their copy. Both are fine.
                println("Could not clear keyboard on chat=${prompt.chatId} msg=${prompt.messageId}: ${e.message}")
            }
        }
    }

    private fun notifyDecision(txId: String, decision: CategorizationBot.Decision) {
        val tx = transactionRepository.get(txId).orElse(null) ?: return
        val household = householdRepository.findHousehold(tx.householdId) ?: return
        val category = when (decision) {
            is CategorizationBot.Decision.Category -> categoryRepository.findById(decision.categoryId) ?: return
            CategorizationBot.Decision.Ignore -> null
        }
        notifyHousehold(household, tx, category)
    }

    private fun buildSaveKeywordKeyboard(householdId: UUID, transactionId: String): InlineKeyboardMarkup {
        val regular = categoryRepository.findAll(householdId)
            .filter { !it.isOther }
            .sortedBy { it.sheetRow }
        val rows = regular.chunked(3).map { chunk ->
            chunk.map { cat ->
                InlineKeyboardButton(cat.displayName).callbackData("k|$transactionId|${cat.sheetRow}")
            }.toTypedArray()
        }
        return InlineKeyboardMarkup(*rows.toTypedArray())
    }

    private fun parseCallbackData(householdId: UUID, data: String): Parsed? {
        val parts = data.split('|')
        if (parts.firstOrNull() != "c" || parts.size != 3) return null
        val sheetRow = parts[2].toIntOrNull() ?: return null
        if (sheetRow == -1) return Parsed(parts[1], CategorizationBot.Decision.Ignore)
        val category = categoryRepository.findBySheetRow(householdId, sheetRow) ?: return null
        return Parsed(parts[1], CategorizationBot.Decision.Category(category.id))
    }

    private fun parseSaveKeywordCallback(householdId: UUID, data: String): SaveKeywordCallback? {
        val parts = data.split('|')
        if (parts.firstOrNull() != "k" || parts.size != 3) return null
        val sheetRow = parts[2].toIntOrNull() ?: return null
        val category = categoryRepository.findBySheetRow(householdId, sheetRow) ?: return null
        return SaveKeywordCallback(parts[1], category.id)
    }

    private fun reply(msg: Message, text: String): Int? =
        gateway.send(msg.chat().id(), text, replyToMessageId = msg.messageId())?.toInt()

    private fun applyLocale(code: String, vararg args: Any?): String {
        val stringArgs: Array<Any?> = Array(args.size) { args[it]?.toString() }
        return messageSource.getMessage(code, stringArgs, Locale.ROOT)
    }

    private data class Parsed(val txId: String, val decision: CategorizationBot.Decision)
    private data class SaveKeywordCallback(val txId: String, val categoryId: UUID)
}

package MailAggregator.MailAggregator.telegram

import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.common.config.Config.Companion.TIME_ZONE
import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.common.usecases.AddCategoryUseCase
import MailAggregator.MailAggregator.common.usecases.HandleTelegramCommentUseCase
import MailAggregator.MailAggregator.common.usecases.SaveKeywordUseCase
import MailAggregator.MailAggregator.monobank.application.MonoTransaction
import MailAggregator.MailAggregator.monobank.repository.TransactionRepository
import MailAggregator.MailAggregator.telegram.model.CategorizationRequest
import MailAggregator.MailAggregator.telegram.repository.TelegramLogMessageRepository
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.UpdatesListener
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.model.request.ReplyParameters
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.EditMessageReplyMarkup
import com.pengrad.telegrambot.request.SendMessage
import jakarta.annotation.PostConstruct
import org.springframework.context.MessageSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

class CategorizationBot(
    private val token: String,
    private val ownerChatId: Long,
    private val categoryRepository: CategoryRepository,
    private val addCategoryUseCase: AddCategoryUseCase,
    private val transactionRepository: TransactionRepository,
    private val telegramLogMessageRepository: TelegramLogMessageRepository,
    private val handleTelegramCommentUseCase: HandleTelegramCommentUseCase,
    private val saveKeywordUseCase: SaveKeywordUseCase,
    private val messageSource: MessageSource,
    private val zoneId: ZoneId = TIME_ZONE,
    private val onDecision: (txId: String, decision: Decision) -> Unit,
) {
    private val bot = TelegramBot(token)
    private val addCategoryStates = java.util.concurrent.ConcurrentHashMap<Long, AddCategoryState>()

    // Input-matching values, loaded once from messages.properties. Lazy so they read the bundle
    // after Spring has finished wiring `messageSource`, not during property initialization.
    private val saveKeywordTrigger: String by lazy { t("trigger.save") }
    private val addCategoryTrigger: String by lazy { t("trigger.addCategory") }
    private val cancelTrigger: String by lazy { t("trigger.cancel") }
    private val emptyKeywordsTrigger: String by lazy { t("trigger.emptyKeywords") }
    private val helpTriggers: Set<String> by lazy {
        t("trigger.help").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }
    private val namePattern: Regex by lazy { Regex(t("validation.namePattern")) }
    private val priorityMin: Int by lazy { t("validation.priority.min").toInt() }
    private val priorityMax: Int by lazy { t("validation.priority.max").toInt() }

    @PostConstruct
    fun startLongPolling() {
        bot.setUpdatesListener({ updates ->
            updates.forEach { handleUpdate(it) }
            UpdatesListener.CONFIRMED_UPDATES_ALL
        }, { e ->
            // логируй e.response()?.description() / network errors
        })
    }

    fun sendTx(transaction: CategorizationRequest) {
        val text = t(
            "tx.prompt",
            transaction.description,
            transaction.transactionId,
            transaction.transactionTime,
            transaction.amount,
        )
        val keyboard = buildKeyboard(transaction.transactionId)

        bot.execute(
            SendMessage(ownerChatId, text).replyMarkup(keyboard),
        )
    }

    private fun handleUpdate(update: Update) {
        val msg = update.message()

        if (msg != null && msg.text() != null) {
            val chatId = msg.chat().id()
            val text = msg.text()

            if (text == "/start") {
                bot.execute(SendMessage(chatId, t("bot.start.greeting", chatId)))
                return
            }

            if (chatId != ownerChatId) return

            val replyTo = msg.replyToMessage()
            val state = addCategoryStates[chatId]

            // Mid-flow cancel: a reply with "Забей" to any bot message kills the flow.
            if (state != null && replyTo != null && replyTo.from()?.isBot == true &&
                text.trim().equals(cancelTrigger, ignoreCase = true)
            ) {
                cancelAddCategoryFlow(chatId, msg)
                return
            }

            // Flow step: only counts when the reply targets the current flow's prompt.
            if (state != null && replyTo != null && replyTo.messageId() == state.lastPromptMessageId) {
                handleAddCategoryStep(chatId, msg, text, state)
                return
            }

            // Trigger phrase as a plain (non-reply) message — restarts the flow if one is running.
            if (replyTo == null && text.trim().equals(addCategoryTrigger, ignoreCase = true)) {
                if (state != null) {
                    addCategoryStates.remove(chatId)
                    reply(msg, t("flow.restarted"))
                }
                startAddCategoryFlow(chatId, msg)
                return
            }

            // Replies to bot tx-log messages (comment / save keyword) — still work mid-flow.
            if (replyTo != null && replyTo.from()?.isBot == true) {
                handleCommentReply(chatId, replyTo.messageId().toLong(), text)
                return
            }

            // Explicit help command.
            if (replyTo == null && text.trim().lowercase() in helpTriggers) {
                sendHelp(msg)
                return
            }

            // Plain non-reply that did not match anything above — point user to /help.
            if (replyTo == null) {
                reply(msg, t("unknown"))
                return
            }
        }

        val cq = update.callbackQuery() ?: return
        val chatId = cq.message()?.chat()?.id()
        if (chatId != ownerChatId) {
            bot.execute(AnswerCallbackQuery(cq.id()).text(t("callback.notAllowed")))
            return
        }

        val data = cq.data() ?: return
        when (data.firstOrNull()) {
            'c' -> handleCategorizationCallback(cq, chatId, data)
            'k' -> handleSaveKeywordCallback(cq, chatId, data)
            else -> bot.execute(AnswerCallbackQuery(cq.id()).text(t("callback.badCallback")))
        }
    }

    private fun handleCategorizationCallback(
        cq: com.pengrad.telegrambot.model.CallbackQuery,
        chatId: Long,
        data: String,
    ) {
        val parsed = parseCallbackData(data) ?: run {
            bot.execute(AnswerCallbackQuery(cq.id()).text(t("callback.badCallback")))
            return
        }

        onDecision(parsed.txId, parsed.decision)

        val message = cq.message()
        if (message != null) {
            bot.execute(EditMessageReplyMarkup(chatId, message.messageId()))
        }
        bot.execute(AnswerCallbackQuery(cq.id()).text(t("callback.saved")))

        sendLogForDecision(parsed.txId, parsed.decision)
    }

    private fun handleSaveKeywordCallback(
        cq: com.pengrad.telegrambot.model.CallbackQuery,
        chatId: Long,
        data: String,
    ) {
        val parsed = parseSaveKeywordCallback(data) ?: run {
            bot.execute(AnswerCallbackQuery(cq.id()).text(t("callback.badCallback")))
            return
        }
        val tx = transactionRepository.get(parsed.txId).orElse(null)
        if (tx == null) {
            bot.execute(AnswerCallbackQuery(cq.id()).text(t("callback.txNotFound")))
            return
        }
        val result = saveKeywordUseCase(parsed.categoryId, tx.raw.description)
        val message = cq.message()
        if (message != null) {
            bot.execute(EditMessageReplyMarkup(chatId, message.messageId()))
        }
        val (callbackText, replyText) = when (result) {
            is SaveKeywordUseCase.Result.Saved ->
                t("callback.saved") to t("savekw.success", result.keyword, result.category.displayName)
            is SaveKeywordUseCase.Result.AlreadyPresent ->
                t("callback.alreadyPresent") to t("savekw.alreadyPresent", result.keyword, result.category.displayName)
            SaveKeywordUseCase.Result.CategoryNotFound ->
                t("callback.categoryNotFound") to t("savekw.categoryNotFound")
            SaveKeywordUseCase.Result.EmptyKeyword ->
                t("callback.empty") to t("savekw.empty")
        }
        bot.execute(AnswerCallbackQuery(cq.id()).text(callbackText))
        bot.execute(SendMessage(chatId, replyText))
    }

    fun sendLog(transaction: MonoTransaction, category: Category?) {
        val zoned = Instant.ofEpochSecond(transaction.raw.time).atZone(zoneId)
        val date = zoned.format(DATE_FORMAT)
        val time = zoned.format(TIME_FORMAT)
        val amount = "%.2f".format(-transaction.raw.amount.toDouble() / 100.0)
        val currency = currencyCode(transaction.raw.currencyCode)
        val tail = category?.let { t("log.tail.category", it.displayName) } ?: t("log.tail.ignored")

        val text = buildString {
            appendLine(t("log.title", transaction.raw.description))
            appendLine(t("log.body", date, time, amount, currency))
            append(tail)
        }
        val response = bot.execute(SendMessage(ownerChatId, text))
        val messageId = response?.message()?.messageId()?.toLong() ?: return
        telegramLogMessageRepository.save(ownerChatId, messageId, transaction.id)
    }

    private fun handleCommentReply(chatId: Long, replyToMessageId: Long, text: String) {
        if (text.isBlank()) return

        if (text.trim().equals(saveKeywordTrigger, ignoreCase = true)) {
            promptSaveKeywordCategory(chatId, replyToMessageId)
            return
        }

        val saved = try {
            handleTelegramCommentUseCase(chatId, replyToMessageId, text)
        } catch (e: Exception) {
            println("Failed to save Telegram comment: ${e.message}")
            bot.execute(SendMessage(chatId, t("comment.saveError")))
            return
        }
        val reply = if (saved) t("comment.saved") else t("comment.notFound")
        bot.execute(SendMessage(chatId, reply))
    }

    private fun promptSaveKeywordCategory(chatId: Long, replyToMessageId: Long) {
        val record = telegramLogMessageRepository.findByChatAndMessage(chatId, replyToMessageId)
        if (record == null) {
            bot.execute(SendMessage(chatId, t("savekw.txMissing")))
            return
        }
        val tx = transactionRepository.get(record.transactionId).orElse(null)
        if (tx == null) {
            bot.execute(SendMessage(chatId, t("savekw.txDbMissing")))
            return
        }
        val description = tx.raw.description.trim()
        if (description.isEmpty()) {
            bot.execute(SendMessage(chatId, t("savekw.emptyDescription")))
            return
        }
        bot.execute(
            SendMessage(chatId, t("savekw.choose", description))
                .replyMarkup(buildSaveKeywordKeyboard(record.transactionId)),
        )
    }

    private fun sendLogForDecision(txId: String, decision: Decision) {
        val tx = transactionRepository.get(txId).orElse(null) ?: return
        val category = when (decision) {
            is Decision.Category -> categoryRepository.findById(decision.categoryId) ?: return
            Decision.Ignore -> null
        }
        sendLog(tx, category)
    }

    private fun startAddCategoryFlow(chatId: Long, msg: Message) {
        val promptId = reply(msg, t("flow.start", cancelTrigger)) ?: return
        addCategoryStates[chatId] = AddCategoryState.AwaitingName(promptId)
    }

    private fun handleAddCategoryStep(chatId: Long, msg: Message, rawText: String, state: AddCategoryState) {
        val text = rawText.trim()
        when (state) {
            is AddCategoryState.AwaitingName -> handleNameStep(chatId, msg, text)
            is AddCategoryState.AwaitingDisplayName -> handleDisplayNameStep(chatId, msg, text, state)
            is AddCategoryState.AwaitingPriority -> handlePriorityStep(chatId, msg, text, state)
            is AddCategoryState.AwaitingKeywords -> handleKeywordsStep(chatId, msg, text, state)
        }
    }

    private fun cancelAddCategoryFlow(chatId: Long, msg: Message) {
        addCategoryStates.remove(chatId)
        reply(msg, t("flow.cancelled"))
    }

    private fun handleNameStep(chatId: Long, msg: Message, name: String) {
        if (!name.matches(namePattern)) {
            val promptId = reply(msg, t("flow.name.bad")) ?: return
            addCategoryStates[chatId] = AddCategoryState.AwaitingName(promptId)
            return
        }
        if (categoryRepository.findByName(name) != null) {
            val promptId = reply(msg, t("flow.name.exists", name)) ?: return
            addCategoryStates[chatId] = AddCategoryState.AwaitingName(promptId)
            return
        }
        val promptId = reply(msg, t("flow.displayName.prompt")) ?: return
        addCategoryStates[chatId] = AddCategoryState.AwaitingDisplayName(promptId, name)
    }

    private fun handleDisplayNameStep(
        chatId: Long,
        msg: Message,
        displayName: String,
        prev: AddCategoryState.AwaitingDisplayName,
    ) {
        if (displayName.isEmpty()) {
            val promptId = reply(msg, t("flow.displayName.empty")) ?: return
            addCategoryStates[chatId] = prev.copy(lastPromptMessageId = promptId)
            return
        }
        val promptId = reply(msg, t("flow.priority.prompt", priorityMin, priorityMax)) ?: return
        addCategoryStates[chatId] = AddCategoryState.AwaitingPriority(promptId, prev.name, displayName)
    }

    private fun handlePriorityStep(
        chatId: Long,
        msg: Message,
        priorityRaw: String,
        prev: AddCategoryState.AwaitingPriority,
    ) {
        val priority = priorityRaw.toIntOrNull()
        if (priority == null || priority !in priorityMin..priorityMax) {
            val promptId = reply(msg, t("flow.priority.bad", priorityMin, priorityMax)) ?: return
            addCategoryStates[chatId] = prev.copy(lastPromptMessageId = promptId)
            return
        }
        val promptId = reply(msg, t("flow.keywords.prompt", emptyKeywordsTrigger)) ?: return
        addCategoryStates[chatId] = AddCategoryState.AwaitingKeywords(promptId, prev.name, prev.displayName, priority)
    }

    private fun handleKeywordsStep(
        chatId: Long,
        msg: Message,
        keywordsRaw: String,
        prev: AddCategoryState.AwaitingKeywords,
    ) {
        val keywords = if (keywordsRaw == emptyKeywordsTrigger) {
            emptyList()
        } else {
            keywordsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        val category = try {
            addCategoryUseCase.add(
                name = prev.name,
                displayName = prev.displayName,
                priority = prev.priority,
                keywords = keywords,
            )
        } catch (e: Exception) {
            println("Failed to add category ${prev.name}: ${e.message}")
            addCategoryStates.remove(chatId)
            reply(msg, t("flow.createFailed", e.message ?: ""))
            return
        }
        addCategoryStates.remove(chatId)
        reply(msg, t("flow.created", category.name, category.displayName, category.sheetRow))
    }

    private fun sendHelp(msg: Message) {
        reply(msg, t("help", addCategoryTrigger, cancelTrigger, saveKeywordTrigger))
    }

    private fun reply(msg: Message, text: String): Int? {
        val response = bot.execute(
            SendMessage(msg.chat().id(), text)
                .replyParameters(ReplyParameters(msg.messageId())),
        )
        return response?.message()?.messageId()
    }

    private fun t(code: String, vararg args: Any?): String {
        val stringArgs: Array<Any?> = Array(args.size) { args[it]?.toString() }
        return messageSource.getMessage(code, stringArgs, Locale.ROOT)
    }

    private fun buildKeyboard(transactionId: String): InlineKeyboardMarkup {
        val all = categoryRepository.findAll()
        val regular = all.filter { !it.isOther }.sortedBy { it.sheetRow }
        val other = all.first { it.isOther }

        val rows = mutableListOf<Array<InlineKeyboardButton>>()

        regular.chunked(3).forEach { chunk ->
            rows += chunk.map { cat ->
                InlineKeyboardButton(cat.displayName).callbackData("c|$transactionId|${cat.sheetRow}")
            }.toTypedArray()
        }

        rows += arrayOf(
            InlineKeyboardButton(t("keyboard.ignore")).callbackData("c|$transactionId|-1"),
            InlineKeyboardButton(t("keyboard.other")).callbackData("c|$transactionId|${other.sheetRow}"),
        )

        return InlineKeyboardMarkup(*rows.toTypedArray())
    }

    private fun buildSaveKeywordKeyboard(transactionId: String): InlineKeyboardMarkup {
        val regular = categoryRepository.findAll()
            .filter { !it.isOther }
            .sortedBy { it.sheetRow }
        val rows = regular.chunked(3).map { chunk ->
            chunk.map { cat ->
                InlineKeyboardButton(cat.displayName).callbackData("k|$transactionId|${cat.sheetRow}")
            }.toTypedArray()
        }
        return InlineKeyboardMarkup(*rows.toTypedArray())
    }

    private fun parseCallbackData(data: String): Parsed? {
        val parts = data.split('|')
        if (parts.firstOrNull() != "c" || parts.size != 3) return null
        val sheetRow = parts[2].toIntOrNull() ?: return null
        if (sheetRow == -1) return Parsed(parts[1], Decision.Ignore)
        val category = categoryRepository.findBySheetRow(sheetRow) ?: return null
        return Parsed(parts[1], Decision.Category(category.id))
    }

    private fun parseSaveKeywordCallback(data: String): SaveKeywordCallback? {
        val parts = data.split('|')
        if (parts.firstOrNull() != "k" || parts.size != 3) return null
        val sheetRow = parts[2].toIntOrNull() ?: return null
        val category = categoryRepository.findBySheetRow(sheetRow) ?: return null
        return SaveKeywordCallback(parts[1], category.id)
    }

    private sealed class AddCategoryState {
        abstract val lastPromptMessageId: Int

        data class AwaitingName(
            override val lastPromptMessageId: Int,
        ) : AddCategoryState()

        data class AwaitingDisplayName(
            override val lastPromptMessageId: Int,
            val name: String,
        ) : AddCategoryState()

        data class AwaitingPriority(
            override val lastPromptMessageId: Int,
            val name: String,
            val displayName: String,
        ) : AddCategoryState()

        data class AwaitingKeywords(
            override val lastPromptMessageId: Int,
            val name: String,
            val displayName: String,
            val priority: Int,
        ) : AddCategoryState()
    }

    private data class Parsed(val txId: String, val decision: Decision)

    private data class SaveKeywordCallback(val txId: String, val categoryId: UUID)

    sealed class Decision {
        data class Category(val categoryId: UUID) : Decision()
        data object Ignore : Decision()
    }

    companion object {
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        private fun currencyCode(numericCode: Int): String = when (numericCode) {
            980 -> "UAH"
            840 -> "USD"
            978 -> "EUR"
            826 -> "GBP"
            else -> "ccy:$numericCode"
        }
    }
}

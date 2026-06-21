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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    private val zoneId: ZoneId = TIME_ZONE,
    private val onDecision: (txId: String, decision: Decision) -> Unit,
) {
    private val bot = TelegramBot(token)
    private val addCategoryStates = java.util.concurrent.ConcurrentHashMap<Long, AddCategoryState>()

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
        val text = buildString {
            appendLine("🧾 ${transaction.description}")
            appendLine("ID: ${transaction.transactionId}")
            appendLine("Time: ${transaction.transactionTime}")
            appendLine("Amount: ${transaction.amount}")
        }

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
                bot.execute(SendMessage(chatId, "OK. Your chatId=$chatId"))
                return
            }

            if (chatId != ownerChatId) return

            val replyTo = msg.replyToMessage()
            val state = addCategoryStates[chatId]

            // Mid-flow cancel: a reply with "Забей" to any bot message kills the flow.
            if (state != null && replyTo != null && replyTo.from()?.isBot == true &&
                text.trim().equals(CANCEL_TRIGGER, ignoreCase = true)
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
            if (replyTo == null && text.trim().equals(ADD_CATEGORY_TRIGGER, ignoreCase = true)) {
                if (state != null) {
                    addCategoryStates.remove(chatId)
                    reply(msg, "ℹ️ Старый флоу прерван, ничего не сохранено. Начинаю заново.")
                }
                startAddCategoryFlow(chatId, msg)
                return
            }

            // Replies to bot tx-log messages (comment / save keyword) — still work mid-flow.
            if (replyTo != null && replyTo.from()?.isBot == true) {
                handleCommentReply(chatId, replyTo.messageId().toLong(), text)
                return
            }
        }

        val cq = update.callbackQuery() ?: return
        val chatId = cq.message()?.chat()?.id()
        if (chatId != ownerChatId) {
            bot.execute(AnswerCallbackQuery(cq.id()).text("Not allowed"))
            return
        }

        val data = cq.data() ?: return
        when (data.firstOrNull()) {
            'c' -> handleCategorizationCallback(cq, chatId, data)
            'k' -> handleSaveKeywordCallback(cq, chatId, data)
            else -> bot.execute(AnswerCallbackQuery(cq.id()).text("Bad callback"))
        }
    }

    private fun handleCategorizationCallback(
        cq: com.pengrad.telegrambot.model.CallbackQuery,
        chatId: Long,
        data: String,
    ) {
        val parsed = parseCallbackData(data) ?: run {
            bot.execute(AnswerCallbackQuery(cq.id()).text("Bad callback"))
            return
        }

        onDecision(parsed.txId, parsed.decision)

        val message = cq.message()
        if (message != null) {
            bot.execute(EditMessageReplyMarkup(chatId, message.messageId()))
        }
        bot.execute(AnswerCallbackQuery(cq.id()).text("Saved"))

        sendLogForDecision(parsed.txId, parsed.decision)
    }

    private fun handleSaveKeywordCallback(
        cq: com.pengrad.telegrambot.model.CallbackQuery,
        chatId: Long,
        data: String,
    ) {
        val parsed = parseSaveKeywordCallback(data) ?: run {
            bot.execute(AnswerCallbackQuery(cq.id()).text("Bad callback"))
            return
        }
        val tx = transactionRepository.get(parsed.txId).orElse(null)
        if (tx == null) {
            bot.execute(AnswerCallbackQuery(cq.id()).text("Tx not found"))
            return
        }
        val result = saveKeywordUseCase(parsed.categoryId, tx.raw.description)
        val message = cq.message()
        if (message != null) {
            bot.execute(EditMessageReplyMarkup(chatId, message.messageId()))
        }
        val (callbackText, replyText) = when (result) {
            is SaveKeywordUseCase.Result.Saved ->
                "Saved" to "✓ Добавил «${result.keyword}» в категорию «${result.category.displayName}»"
            is SaveKeywordUseCase.Result.AlreadyPresent ->
                "Already present" to "ℹ️ «${result.keyword}» уже в категории «${result.category.displayName}»"
            SaveKeywordUseCase.Result.CategoryNotFound ->
                "Category not found" to "❌ Категория не найдена"
            SaveKeywordUseCase.Result.EmptyKeyword ->
                "Empty" to "❌ Пустое описание, нечего сохранять"
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
        val tail = category?.let { "Категория: ${it.displayName}" } ?: "Игнорировано"

        val text = buildString {
            appendLine("🧾 ${transaction.raw.description}")
            appendLine("$date $time  −$amount $currency")
            append(tail)
        }
        val response = bot.execute(SendMessage(ownerChatId, text))
        val messageId = response?.message()?.messageId()?.toLong() ?: return
        telegramLogMessageRepository.save(ownerChatId, messageId, transaction.id)
    }

    private fun handleCommentReply(chatId: Long, replyToMessageId: Long, text: String) {
        if (text.isBlank()) return

        if (text.trim().equals(SAVE_KEYWORD_TRIGGER, ignoreCase = true)) {
            promptSaveKeywordCategory(chatId, replyToMessageId)
            return
        }

        val saved = try {
            handleTelegramCommentUseCase(chatId, replyToMessageId, text)
        } catch (e: Exception) {
            println("Failed to save Telegram comment: ${e.message}")
            bot.execute(SendMessage(chatId, "❌ Не получилось сохранить коммент"))
            return
        }
        val reply = if (saved) "✓ Comment saved" else "❌ Не нашёл транзакцию для этого сообщения"
        bot.execute(SendMessage(chatId, reply))
    }

    private fun promptSaveKeywordCategory(chatId: Long, replyToMessageId: Long) {
        val record = telegramLogMessageRepository.findByChatAndMessage(chatId, replyToMessageId)
        if (record == null) {
            bot.execute(SendMessage(chatId, "❌ Не нашёл транзакцию для этого сообщения"))
            return
        }
        val tx = transactionRepository.get(record.transactionId).orElse(null)
        if (tx == null) {
            bot.execute(SendMessage(chatId, "❌ Транзакция не найдена в БД"))
            return
        }
        val description = tx.raw.description.trim()
        if (description.isEmpty()) {
            bot.execute(SendMessage(chatId, "❌ У транзакции пустое описание, нечего сохранять"))
            return
        }
        bot.execute(
            SendMessage(chatId, "Выбери категорию для «$description»:")
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
        val promptId = reply(
            msg,
            "Дай название категории (заглавные латинские буквы и `_`). " +
                "На любом шаге ответь «$CANCEL_TRIGGER», чтобы выйти.",
        ) ?: return
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
        reply(msg, "Окей, забил.")
    }

    private fun handleNameStep(chatId: Long, msg: Message, name: String) {
        if (!name.matches(NAME_PATTERN)) {
            val promptId = reply(msg, "Имя должно быть из заглавных латинских букв и `_`. Попробуй ещё:") ?: return
            addCategoryStates[chatId] = AddCategoryState.AwaitingName(promptId)
            return
        }
        if (categoryRepository.findByName(name) != null) {
            val promptId = reply(msg, "Категория «$name» уже существует. Дай другое имя:") ?: return
            addCategoryStates[chatId] = AddCategoryState.AwaitingName(promptId)
            return
        }
        val promptId = reply(msg, "Дай читаемое название:") ?: return
        addCategoryStates[chatId] = AddCategoryState.AwaitingDisplayName(promptId, name)
    }

    private fun handleDisplayNameStep(
        chatId: Long,
        msg: Message,
        displayName: String,
        prev: AddCategoryState.AwaitingDisplayName,
    ) {
        if (displayName.isEmpty()) {
            val promptId = reply(msg, "Пустое не подходит. Дай читаемое название:") ?: return
            addCategoryStates[chatId] = prev.copy(lastPromptMessageId = promptId)
            return
        }
        val promptId = reply(msg, "Дай приоритет ($PRIORITY_MIN..$PRIORITY_MAX):") ?: return
        addCategoryStates[chatId] = AddCategoryState.AwaitingPriority(promptId, prev.name, displayName)
    }

    private fun handlePriorityStep(
        chatId: Long,
        msg: Message,
        priorityRaw: String,
        prev: AddCategoryState.AwaitingPriority,
    ) {
        val priority = priorityRaw.toIntOrNull()
        if (priority == null || priority !in PRIORITY_MIN..PRIORITY_MAX) {
            val promptId = reply(msg, "Нужно число от $PRIORITY_MIN до $PRIORITY_MAX. Попробуй ещё:") ?: return
            addCategoryStates[chatId] = prev.copy(lastPromptMessageId = promptId)
            return
        }
        val promptId = reply(
            msg,
            "Дай ключевые слова через запятую, или «$EMPTY_KEYWORDS_TRIGGER» чтобы оставить пустым " +
                "(тогда добавишь через «Сохранить» в будущем):",
        ) ?: return
        addCategoryStates[chatId] = AddCategoryState.AwaitingKeywords(promptId, prev.name, prev.displayName, priority)
    }

    private fun handleKeywordsStep(
        chatId: Long,
        msg: Message,
        keywordsRaw: String,
        prev: AddCategoryState.AwaitingKeywords,
    ) {
        val keywords = if (keywordsRaw == EMPTY_KEYWORDS_TRIGGER) {
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
            reply(msg, "❌ Не получилось создать категорию: ${e.message}")
            return
        }
        addCategoryStates.remove(chatId)
        reply(
            msg,
            "✓ Создал «${category.name}» (${category.displayName}) на sheet_row=${category.sheetRow}.",
        )
    }

    private fun reply(msg: Message, text: String): Int? {
        val response = bot.execute(
            SendMessage(msg.chat().id(), text)
                .replyParameters(ReplyParameters(msg.messageId())),
        )
        return response?.message()?.messageId()
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
            InlineKeyboardButton("Игнорировать").callbackData("c|$transactionId|-1"),
            InlineKeyboardButton("Другое").callbackData("c|$transactionId|${other.sheetRow}"),
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
        private const val SAVE_KEYWORD_TRIGGER = "Сохранить"
        private const val ADD_CATEGORY_TRIGGER = "Добавить категорию"
        private const val CANCEL_TRIGGER = "Забей"
        private const val EMPTY_KEYWORDS_TRIGGER = "-"
        private const val PRIORITY_MIN = 1
        private const val PRIORITY_MAX = 100
        private val NAME_PATTERN = Regex("[A-Z_]+")
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

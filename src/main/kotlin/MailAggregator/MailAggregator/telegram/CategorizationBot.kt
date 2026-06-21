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
            when {
                text == "/start" -> {
                    bot.execute(SendMessage(chatId, "OK. Your chatId=$chatId"))
                    return
                }
                text.startsWith("/addcategory") -> {
                    if (chatId != ownerChatId) return
                    handleAddCategory(msg)
                    return
                }
            }
            val replyTo = msg.replyToMessage()
            if (replyTo != null && chatId == ownerChatId && replyTo.from()?.isBot == true) {
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

    private fun handleAddCategory(msg: Message) {
        val chatId = msg.chat().id()
        val parsed = parseAddCategoryCommand(msg.text())
        if (parsed == null) {
            bot.execute(
                SendMessage(
                    chatId,
                    "Usage: /addcategory NAME \"Display name\" priority kw1,kw2,...\n" +
                        "Example: /addcategory PETS \"Питомцы\" 50 zoo,корм для котов,vet",
                ),
            )
            return
        }

        if (categoryRepository.findByName(parsed.name) != null) {
            bot.execute(SendMessage(chatId, "Category '${parsed.name}' already exists."))
            return
        }

        val category = addCategoryUseCase.add(
            name = parsed.name,
            displayName = parsed.displayName,
            priority = parsed.priority,
            keywords = parsed.keywords,
        )
        bot.execute(
            SendMessage(
                chatId,
                "OK. Created '${category.name}' (${category.displayName}) at sheet_row=${category.sheetRow}.",
            ),
        )
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

    private fun parseAddCategoryCommand(text: String): AddCategoryArgs? {
        val body = text.removePrefix("/addcategory").trim()
        if (body.isEmpty()) return null

        val nameAndRest = body.split(' ', limit = 2)
        if (nameAndRest.size < 2) return null
        val name = nameAndRest[0].trim()
        if (!name.matches(Regex("[A-Z_]+"))) return null
        var rest = nameAndRest[1].trim()

        if (!rest.startsWith('"')) return null
        val closing = rest.indexOf('"', startIndex = 1)
        if (closing <= 1) return null
        val displayName = rest.substring(1, closing)
        rest = rest.substring(closing + 1).trim()

        val priorityAndRest = rest.split(' ', limit = 2)
        val priority = priorityAndRest.getOrNull(0)?.toIntOrNull() ?: return null
        val keywordsRaw = priorityAndRest.getOrNull(1)?.trim().orEmpty()
        val keywords = if (keywordsRaw.isEmpty()) {
            emptyList()
        } else {
            keywordsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }

        return AddCategoryArgs(name, displayName, priority, keywords)
    }

    private data class AddCategoryArgs(
        val name: String,
        val displayName: String,
        val priority: Int,
        val keywords: List<String>,
    )

    private data class Parsed(val txId: String, val decision: Decision)

    private data class SaveKeywordCallback(val txId: String, val categoryId: UUID)

    sealed class Decision {
        data class Category(val categoryId: UUID) : Decision()
        data object Ignore : Decision()
    }

    companion object {
        private const val SAVE_KEYWORD_TRIGGER = "Сохранить"
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

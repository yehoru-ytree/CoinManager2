package MailAggregator.MailAggregator.telegram

import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.common.config.Config.Companion.TIME_ZONE
import MailAggregator.MailAggregator.telegram.model.CategorizationRequest
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.UpdatesListener
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.EditMessageReplyMarkup
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import jakarta.annotation.PostConstruct
import java.time.ZoneId

class CategorizationBot(
    private val token: String,
    private val ownerChatId: Long,
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
            SendMessage(ownerChatId, text).replyMarkup(keyboard)
        )
    }

    private fun handleUpdate(update: Update) {
        val msg = update.message()
        if (msg != null && msg.text() == "/start") {
            val chatId = msg.chat().id()
            bot.execute(SendMessage(chatId, "OK. Your chatId=$chatId"))
            return
        }

        val cq = update.callbackQuery() ?: return
        val chatId = cq.message()?.chat()?.id()
        if (chatId != ownerChatId) {
            bot.execute(AnswerCallbackQuery(cq.id()).text("Not allowed"))
            return
        }

        val data = cq.data() ?: return // data приходит из callback_data :contentReference[oaicite:4]{index=4}
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
    }

    private fun buildKeyboard(transactionId: String): InlineKeyboardMarkup {
        val rows = mutableListOf<Array<InlineKeyboardButton>>()

        Category.entries
            .filter { it != Category.OTHER } // Other separately in the end
            .chunked(3)
            .forEach { chunk ->
                rows += chunk.map { cat ->
                    InlineKeyboardButton(cat.displayName).callbackData("c|$transactionId|${cat.index}")
                }.toTypedArray()
            }

        rows += arrayOf(
            InlineKeyboardButton("Игнорировать").callbackData("c|$transactionId|-1"),
            InlineKeyboardButton("Другое").callbackData("c|$transactionId|21"),
        )

        return InlineKeyboardMarkup(*rows.toTypedArray())
    }

    private fun parseCallbackData(data: String): Parsed? {
        // c|<txId>|<CAT>/-1
        val parts = data.split('|')
        return when (parts.firstOrNull()) {
            "c" -> if (parts.size == 3){
                if (parts[2] == "-1") {
                    Parsed(parts[1], Decision.Ignore)
                } else {
                    Parsed(parts[1], Decision.Category(Category.fromIndex(parts[2].toInt())))
                }
            } else null
            else -> null
        }
    }

    private data class Parsed(val txId: String, val decision: Decision)

    sealed class Decision {
        data class Category(val category: MailAggregator.MailAggregator.common.Category) : Decision()
        data object Ignore : Decision()
    }
}
package MailAggregator.MailAggregator.telegram

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.UpdatesListener
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.model.request.ReplyParameters
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.EditMessageReplyMarkup
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.BaseResponse
import org.slf4j.LoggerFactory

class PengradTelegramGateway(token: String) : TelegramGateway {
    private val log = LoggerFactory.getLogger(PengradTelegramGateway::class.java)
    private val bot = TelegramBot(token)

    override fun start(onUpdate: (Update) -> Unit) {
        bot.setUpdatesListener(
            { updates ->
                updates.forEach { update ->
                    // One bad update must not take the rest of the batch down with it, and must
                    // leave a trace — otherwise the user just sees the bot go quiet.
                    try {
                        onUpdate(update)
                    } catch (e: Exception) {
                        log.error("Failed to handle Telegram update {}", update.updateId(), e)
                    }
                }
                UpdatesListener.CONFIRMED_UPDATES_ALL
            },
            { e -> log.warn("Telegram long-polling error: {}", e.response()?.description() ?: e.message, e) },
        )
    }

    override fun send(
        chatId: Long,
        text: String,
        keyboard: InlineKeyboardMarkup?,
        replyToMessageId: Int?,
    ): Long? {
        val request = SendMessage(chatId, text)
        keyboard?.let(request::replyMarkup)
        replyToMessageId?.let { request.replyParameters(ReplyParameters(it)) }
        return execute(request, "sendMessage chat=$chatId")?.message()?.messageId()?.toLong()
    }

    override fun editKeyboard(chatId: Long, messageId: Long, keyboard: InlineKeyboardMarkup?) {
        val request = EditMessageReplyMarkup(chatId, messageId.toInt())
        keyboard?.let(request::replyMarkup)
        execute(request, "editMessageReplyMarkup chat=$chatId msg=$messageId")
    }

    override fun answerCallback(callbackQueryId: String, text: String?) {
        val request = AnswerCallbackQuery(callbackQueryId)
        text?.let(request::text)
        execute(request, "answerCallbackQuery")
    }

    /**
     * Telegram reports API-level failures (e.g. BUTTON_DATA_INVALID) as a response with `ok=false`,
     * not an exception. Callers treat a `null` message id as "skip", so without this log a rejected
     * message vanishes silently.
     */
    private fun <T : BaseRequest<T, R>, R : BaseResponse> execute(request: T, what: String): R? {
        val response = bot.execute(request)
        if (response == null || !response.isOk) {
            log.warn(
                "Telegram {} failed: {} {}",
                what,
                response?.errorCode(),
                response?.description(),
            )
        }
        return response
    }
}

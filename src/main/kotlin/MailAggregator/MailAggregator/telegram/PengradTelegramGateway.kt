package MailAggregator.MailAggregator.telegram

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.UpdatesListener
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.model.request.ReplyParameters
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.EditMessageReplyMarkup
import com.pengrad.telegrambot.request.SendMessage

class PengradTelegramGateway(token: String) : TelegramGateway {
    private val bot = TelegramBot(token)

    override fun start(onUpdate: (Update) -> Unit) {
        bot.setUpdatesListener(
            { updates ->
                updates.forEach { onUpdate(it) }
                UpdatesListener.CONFIRMED_UPDATES_ALL
            },
            { /* log e.response()?.description() / network errors */ },
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
        return bot.execute(request)?.message()?.messageId()?.toLong()
    }

    override fun editKeyboard(chatId: Long, messageId: Long, keyboard: InlineKeyboardMarkup?) {
        val request = EditMessageReplyMarkup(chatId, messageId.toInt())
        keyboard?.let(request::replyMarkup)
        bot.execute(request)
    }

    override fun answerCallback(callbackQueryId: String, text: String?) {
        val request = AnswerCallbackQuery(callbackQueryId)
        text?.let(request::text)
        bot.execute(request)
    }
}

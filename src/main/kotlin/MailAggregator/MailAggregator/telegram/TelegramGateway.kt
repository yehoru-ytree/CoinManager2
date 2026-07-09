package MailAggregator.MailAggregator.telegram

import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup

interface TelegramGateway {
    fun start(onUpdate: (Update) -> Unit)

    fun send(
        chatId: Long,
        text: String,
        keyboard: InlineKeyboardMarkup? = null,
        replyToMessageId: Int? = null,
    ): Long?

    fun editKeyboard(chatId: Long, messageId: Long, keyboard: InlineKeyboardMarkup? = null)

    fun answerCallback(callbackQueryId: String, text: String? = null)
}

package MailAggregator.MailAggregator.telegram

import MailAggregator.MailAggregator.bank.Transaction
import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.common.config.Config.Companion.TIME_ZONE
import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.household.Household
import MailAggregator.MailAggregator.household.repository.HouseholdRepository
import MailAggregator.MailAggregator.telegram.model.CategorizationRequest
import MailAggregator.MailAggregator.telegram.repository.TelegramLogMessageRepository
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import org.springframework.context.MessageSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Public bot surface: two broadcasts ([sendTx] / [sendLog]) that fan messages out to household
 * members, plus a plain [notifyChat] DM used by the email ingestor to relay Gmail-forwarding
 * verification codes to a specific chat without going through the keyword/category pipeline.
 *
 * Routing (Wizards, PlainCommandHandler, transaction-callback handling) lives in [UpdateRouter];
 * long-polling startup is wired there via `@PostConstruct`. This class is deliberately unaware
 * of the router — it only depends on its broadcast collaborators.
 */
class CategorizationBot(
    private val gateway: TelegramGateway,
    private val categoryRepository: CategoryRepository,
    private val householdRepository: HouseholdRepository,
    private val telegramLogMessageRepository: TelegramLogMessageRepository,
    private val messageSource: MessageSource,
    private val zoneId: ZoneId = TIME_ZONE,
) {

    /** Plain DM to a single chat — used by the email ingestor to relay Gmail forwarding
     *  verification codes to the user without going through the keyword/category pipeline. */
    fun notifyChat(chatId: Long, text: String) {
        gateway.send(chatId, text)
    }

    fun sendTx(transaction: CategorizationRequest) {
        val text = applyLocale(
            "tx.prompt",
            transaction.description,
            transaction.transactionId,
            transaction.transactionTime,
            transaction.amount,
        )
        val keyboard = buildKeyboard(transaction.householdId, transaction.transactionId)
        val users = householdRepository.findUsersInHousehold(transaction.householdId)
        for (user in users) {
            val messageId = gateway.send(user.chatId, text, keyboard = keyboard) ?: continue
            // Persist so we can edit ALL members' keyboards away once the first one taps,
            // and so any member's reply (Сохранить / коммент) on the prompt routes back to the tx.
            telegramLogMessageRepository.save(
                transaction.householdId,
                user.chatId,
                messageId,
                transaction.transactionId,
            )
        }
    }

    fun sendLog(household: Household, transaction: Transaction, category: Category?) {
        val zoned = Instant.ofEpochSecond(transaction.time).atZone(zoneId)
        val date = zoned.format(DATE_FORMAT)
        val time = zoned.format(TIME_FORMAT)
        val amount = "%.2f".format(-transaction.amount.toDouble() / 100.0)
        val currency = currencyCode(transaction.currencyCode)
        val tail = category?.let { applyLocale("log.tail.category", it.displayName) } ?: applyLocale("log.tail.ignored")

        val text = buildString {
            appendLine(applyLocale("log.title", transaction.description))
            appendLine(applyLocale("log.body", date, time, amount, currency))
            append(tail)
        }

        // For manually categorised tx the prompt with the keyboard was sent first (by sendTx) and
        // recorded here. Thread the log under each user's copy of that prompt so the chat keeps a
        // visual «question → answer» history. Auto-categorised transactions have no prior prompt;
        // those rows just don't exist yet and the log goes out as a fresh message.
        val priorMessages = telegramLogMessageRepository.findAllByTransactionId(transaction.id)
            .associateBy { it.chatId }

        val users = householdRepository.findUsersInHousehold(household.id)
        for (user in users) {
            val replyToId = priorMessages[user.chatId]?.messageId?.toInt()
            val messageId = gateway.send(user.chatId, text, replyToMessageId = replyToId) ?: continue
            telegramLogMessageRepository.save(household.id, user.chatId, messageId, transaction.id)
        }
    }

    private fun applyLocale(code: String, vararg args: Any?): String {
        val stringArgs: Array<Any?> = Array(args.size) { args[it]?.toString() }
        return messageSource.getMessage(code, stringArgs, Locale.ROOT)
    }

    private fun buildKeyboard(householdId: UUID, transactionId: String): InlineKeyboardMarkup {
        val all = categoryRepository.findAll(householdId)
        val regular = all.filter { !it.isOther }.sortedBy { it.sheetRow }
        val other = all.first { it.isOther }

        val rows = mutableListOf<Array<InlineKeyboardButton>>()
        regular.chunked(3).forEach { chunk ->
            rows += chunk.map { cat ->
                InlineKeyboardButton(cat.displayName).callbackData("c|$transactionId|${cat.sheetRow}")
            }.toTypedArray()
        }
        rows += arrayOf(
            InlineKeyboardButton(applyLocale("keyboard.ignore")).callbackData("c|$transactionId|-1"),
            InlineKeyboardButton(applyLocale("keyboard.other")).callbackData("c|$transactionId|${other.sheetRow}"),
        )
        return InlineKeyboardMarkup(*rows.toTypedArray())
    }

    /** User's decision on a categorisation prompt — surfaced to the outside world via the
     *  onDecision lambda held by [PlainCommandHandler]. */
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

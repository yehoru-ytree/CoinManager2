package MailAggregator.MailAggregator.telegram.wizard

import MailAggregator.MailAggregator.bank.BankType
import MailAggregator.MailAggregator.household.BotUser
import MailAggregator.MailAggregator.household.repository.HouseholdRepository
import MailAggregator.MailAggregator.household.usecase.AddBankAccountUseCase
import MailAggregator.MailAggregator.monobank.api.MonoApiAccount
import MailAggregator.MailAggregator.monobank.api.MonobankApi
import MailAggregator.MailAggregator.telegram.TelegramGateway
import com.pengrad.telegrambot.model.CallbackQuery
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import org.springframework.context.MessageSource
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AddCardWizard(
    private val gateway: TelegramGateway,
    private val addBankAccountUseCase: AddBankAccountUseCase,
    private val monobankApi: MonobankApi,
    private val householdRepository: HouseholdRepository,
    private val messageSource: MessageSource,
    /** Local part / domain of the aggregator ingest inbox — used to compose Privat email aliases. */
    private val ingestEmail: String,
) : Wizard {

    private val states = ConcurrentHashMap<Long, State>()

    private val addCardTrigger: String by lazy { translate("trigger.addCard") }
    private val cancelTrigger: String by lazy { translate("trigger.cancel") }

    override fun hasState(chatId: Long): Boolean = states.containsKey(chatId)

    override fun resetState(chatId: Long): Boolean = states.remove(chatId) != null

    override fun tryHandleMidFlow(context: MessageContext): Boolean {
        val state = states[context.chatId] ?: return false
        val replyTo = context.replyTo ?: return false

        if (replyTo.from()?.isBot == true && context.text.trim().equals(cancelTrigger, ignoreCase = true)) {
            states.remove(context.chatId)
            reply(context.msg, translate("flow.cancelled"))
            return true
        }
        if (replyTo.messageId() == state.lastPromptMessageId) {
            val user = context.user ?: return true
            handleStep(context.chatId, context.msg, context.text.trim(), state, user)
            return true
        }
        return false
    }

    override fun matchesStartTrigger(context: MessageContext): Boolean =
        context.user != null &&
            context.replyTo == null &&
            context.text.trim().equals(addCardTrigger, ignoreCase = true)

    override fun start(context: MessageContext) {
        val keyboard = InlineKeyboardMarkup(
            arrayOf(
                InlineKeyboardButton(translate("keyboard.bank.mono")).callbackData("b|mono"),
                InlineKeyboardButton(translate("keyboard.bank.privat")).callbackData("b|privat"),
            ),
        )
        val promptId = gateway.send(
            context.msg.chat().id(),
            translate("addCard.start", cancelTrigger),
            keyboard = keyboard,
            replyToMessageId = context.msg.messageId(),
        ) ?: return
        states[context.chatId] = State.AwaitingBankChoice(promptId.toInt())
    }

    override fun tryHandleCallback(context: CallbackContext): Boolean {
        val prefix = context.data.firstOrNull() ?: return false
        return when (prefix) {
            'b' -> { handleBankPickerCallback(context.cq, context.chatId, context.user, context.data); true }
            'm' -> { handleMonoAccountCallback(context.cq, context.chatId, context.user, context.data); true }
            else -> false
        }
    }

    private fun handleStep(chatId: Long, msg: Message, text: String, state: State, user: BotUser) {
        when (state) {
            // Picker phase consumes a button tap, not a text reply — ignore stray text replies.
            is State.AwaitingBankChoice -> return
            is State.Mono -> handleMonoStep(chatId, msg, text, state, user)
        }
    }

    private fun handleMonoStep(chatId: Long, msg: Message, text: String, state: State.Mono, user: BotUser) {
        when (state) {
            is State.Mono.AwaitingToken -> {
                if (text.isEmpty()) {
                    val promptId = reply(msg, translate("addCard.mono.emptyToken")) ?: return
                    states[chatId] = State.Mono.AwaitingToken(promptId)
                    return
                }
                val accounts = try {
                    monobankApi.getClientInfo(text).accounts
                } catch (e: Exception) {
                    println("Mono getClientInfo failed for chat $chatId: ${e.message}")
                    states.remove(chatId)
                    reply(msg, translate("addCard.mono.tokenFailed", e.message ?: ""))
                    return
                }
                when (accounts.size) {
                    0 -> {
                        states.remove(chatId)
                        reply(msg, translate("addCard.mono.noAccounts"))
                    }
                    1 -> persistBankAccount(
                        chatId, msg, user, BankType.MONOBANK,
                        token = text, accountId = accounts[0].id, clientId = null,
                    )
                    else -> {
                        val keyboard = InlineKeyboardMarkup(
                            *accounts.map { acc ->
                                arrayOf(InlineKeyboardButton(formatMonoAccountLabel(acc)).callbackData("m|${acc.id}"))
                            }.toTypedArray(),
                        )
                        val promptId = gateway.send(
                            msg.chat().id(),
                            translate("addCard.mono.askAccountChoice", accounts.size),
                            keyboard = keyboard,
                            replyToMessageId = msg.messageId(),
                        ) ?: return
                        states[chatId] = State.Mono.AwaitingAccountChoice(
                            promptId.toInt(), text, accounts.map { it.id }.toSet(),
                        )
                    }
                }
            }
            is State.Mono.AwaitingAccountChoice -> return // tap, not text
        }
    }

    private fun handleBankPickerCallback(cq: CallbackQuery, chatId: Long, user: BotUser, data: String) {
        val state = states[chatId] as? State.AwaitingBankChoice
        val pickerMsg = cq.message()
        if (state == null || pickerMsg == null || pickerMsg.messageId() != state.lastPromptMessageId) {
            gateway.answerCallback(cq.id(), translate("callback.alreadyDone"))
            return
        }
        val choice = data.split('|').getOrNull(1)
        gateway.editKeyboard(chatId, pickerMsg.messageId().toLong())
        gateway.answerCallback(cq.id())

        when (choice) {
            "mono" -> {
                val promptId = gateway.send(
                    chatId,
                    translate("addCard.mono.askToken"),
                    replyToMessageId = pickerMsg.messageId(),
                ) ?: return
                states[chatId] = State.Mono.AwaitingToken(promptId.toInt())
            }
            "privat" -> {
                states.remove(chatId)
                startPrivatEmailOnboarding(chatId, pickerMsg, cq.from(), user)
            }
            else -> {
                states.remove(chatId)
                gateway.send(chatId, translate("addCard.bankNotFound"))
            }
        }
    }

    private fun handleMonoAccountCallback(cq: CallbackQuery, chatId: Long, user: BotUser, data: String) {
        val state = states[chatId] as? State.Mono.AwaitingAccountChoice
        val pickerMsg = cq.message()
        val accountId = data.substringAfter('|', missingDelimiterValue = "")
        if (state == null || pickerMsg == null ||
            pickerMsg.messageId() != state.lastPromptMessageId ||
            accountId.isEmpty() || accountId !in state.knownAccountIds
        ) {
            gateway.answerCallback(cq.id(), translate("callback.alreadyDone"))
            return
        }
        gateway.editKeyboard(chatId, pickerMsg.messageId().toLong())
        gateway.answerCallback(cq.id())
        persistBankAccount(
            chatId, pickerMsg, user, BankType.MONOBANK,
            token = state.token, accountId = accountId, clientId = null,
            actorDisplayName = displayNameOf(cq.from()),
        )
    }

    // PrivatBank doesn't expose a personal HTTP API; instead the user configures a Gmail filter
    // that forwards Privat's notification emails to a per-user alias of our ingest mailbox. The
    // bot just generates that suffix, persists the (user, suffix) link as a BankAccount row, and
    // sends the user the one-time setup instructions. Verification of the Gmail forwarding
    // address is handled silently by PrivatEmailIngestor.
    private fun startPrivatEmailOnboarding(
        chatId: Long,
        replyTo: Message,
        tgUser: com.pengrad.telegrambot.model.User?,
        user: BotUser,
    ) {
        val existing = addBankAccountUseCase.findFirstPrivatForUser(user)
        val suffix = existing?.accountId ?: generatePrivatAliasSuffix(tgUser)
        if (existing == null) {
            try {
                addBankAccountUseCase.add(user, BankType.PRIVATBANK, token = "", accountId = suffix, clientId = null)
            } catch (e: Exception) {
                println("Failed to register Privat email link for chat $chatId: ${e.message}")
                gateway.send(
                    chatId,
                    translate("addCard.failed", e.message ?: ""),
                    replyToMessageId = replyTo.messageId(),
                )
                return
            }
        }
        val aliasEmail = composeAliasEmail(suffix)
        gateway.send(
            chatId,
            translate("addCard.privat.instructions", aliasEmail),
            replyToMessageId = replyTo.messageId(),
        )
        if (existing == null) {
            broadcastInfo(
                user.householdId,
                excludeChatId = chatId,
                text = translate("addCard.broadcast", displayNameOf(tgUser)),
            )
        }
    }

    private fun generatePrivatAliasSuffix(tgUser: com.pengrad.telegrambot.model.User?): String {
        val base = (tgUser?.firstName() ?: tgUser?.username() ?: "user")
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")
            .take(12)
            .ifBlank { "user" }
        val random = (0 until 4).map { "0123456789abcdef".random() }.joinToString("")
        return "$base-$random"
    }

    private fun composeAliasEmail(suffix: String): String {
        val (local, domain) = ingestEmail.split('@', limit = 2).let {
            if (it.size == 2) it[0] to it[1] else ingestEmail to "gmail.com"
        }
        return "$local+$suffix@$domain"
    }

    // Inline-button label for a Mono account: "type · CCY · …last-4-of-IBAN" — fits in <40 chars
    // and gives the user enough to pick the right card without having to memorise the UUID.
    private fun formatMonoAccountLabel(account: MonoApiAccount): String {
        val type = account.type?.takeIf { it.isNotBlank() } ?: "account"
        val currency = currencyCode(account.currencyCode)
        val ibanTail = account.iban?.takeLast(4)?.let { " · …$it" } ?: ""
        return "$type · $currency$ibanTail"
    }

    private fun persistBankAccount(
        chatId: Long,
        msg: Message,
        user: BotUser,
        bankType: BankType,
        token: String,
        accountId: String,
        clientId: String?,
        actorDisplayName: String = displayNameOf(msg),
    ) {
        try {
            addBankAccountUseCase.add(user, bankType, token, accountId, clientId)
        } catch (e: Exception) {
            println("Failed to add $bankType account for chat $chatId: ${e.message}")
            states.remove(chatId)
            reply(msg, translate("addCard.failed", e.message ?: ""))
            return
        }
        states.remove(chatId)
        reply(msg, translate("addCard.success"))
        broadcastInfo(
            user.householdId,
            excludeChatId = chatId,
            text = translate("addCard.broadcast", actorDisplayName),
        )
    }

    private fun broadcastInfo(householdId: UUID, excludeChatId: Long, text: String) {
        householdRepository.findUsersInHousehold(householdId)
            .filter { it.chatId != excludeChatId }
            .forEach { other ->
                try {
                    gateway.send(other.chatId, text)
                } catch (e: Exception) {
                    println("Failed to broadcast to chat=${other.chatId}: ${e.message}")
                }
            }
    }

    private fun displayNameOf(msg: Message): String = displayNameOf(msg.from())

    private fun displayNameOf(tgUser: com.pengrad.telegrambot.model.User?): String =
        tgUser?.firstName()?.takeIf { it.isNotBlank() }
            ?: tgUser?.username()?.takeIf { it.isNotBlank() }
            ?: translate("displayName.unknown")

    private fun reply(msg: Message, text: String): Int? =
        gateway.send(msg.chat().id(), text, replyToMessageId = msg.messageId())?.toInt()

    private fun translate(code: String, vararg args: Any?): String {
        val stringArgs: Array<Any?> = Array(args.size) { args[it]?.toString() }
        return messageSource.getMessage(code, stringArgs, Locale.ROOT)
    }

    private fun currencyCode(numericCode: Int): String = when (numericCode) {
        980 -> "UAH"
        840 -> "USD"
        978 -> "EUR"
        826 -> "GBP"
        else -> numericCode.toString()
    }

    private sealed class State {
        abstract val lastPromptMessageId: Int

        data class AwaitingBankChoice(override val lastPromptMessageId: Int) : State()

        sealed class Mono : State() {
            data class AwaitingToken(override val lastPromptMessageId: Int) : Mono()
            data class AwaitingAccountChoice(
                override val lastPromptMessageId: Int,
                val token: String,
                val knownAccountIds: Set<String>,
            ) : Mono()
        }
    }
}

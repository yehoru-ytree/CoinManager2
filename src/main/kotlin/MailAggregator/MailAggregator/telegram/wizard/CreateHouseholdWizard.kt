package MailAggregator.MailAggregator.telegram.wizard

import MailAggregator.MailAggregator.household.repository.HouseholdRepository
import MailAggregator.MailAggregator.household.usecase.CreateHouseholdUseCase
import MailAggregator.MailAggregator.spreadsheet.Authentication
import MailAggregator.MailAggregator.telegram.TelegramGateway
import com.pengrad.telegrambot.model.Message
import org.springframework.context.MessageSource
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class CreateHouseholdWizard(
    private val gateway: TelegramGateway,
    private val householdRepository: HouseholdRepository,
    private val createHouseholdUseCase: CreateHouseholdUseCase,
    private val authentication: Authentication,
    private val messageSource: MessageSource,
) : Wizard {

    override val requiresRegistration: Boolean = false // public wizard: how a fresh chat bootstraps

    private val states = ConcurrentHashMap<Long, State>()

    private val createHouseholdTrigger: String by lazy { applyLocale("trigger.createHousehold") }
    private val cancelTrigger: String by lazy { applyLocale("trigger.cancel") }
    private val addCardTrigger: String by lazy { applyLocale("trigger.addCard") }

    override fun hasState(chatId: Long): Boolean = states.containsKey(chatId)

    override fun resetState(chatId: Long): Boolean = states.remove(chatId) != null

    override fun tryHandleMidFlow(context: MessageContext): Boolean {
        val state = states[context.chatId] ?: return false
        val replyTo = context.replyTo ?: return false

        // Cancel-check runs before step-check: replying with the cancel trigger to the current
        // prompt must abort the wizard, not be parsed as a sheet id (matches AddCategory / AddCard).
        if (replyTo.from()?.isBot == true && context.text.trim().equals(cancelTrigger, ignoreCase = true)) {
            states.remove(context.chatId)
            reply(context.msg, applyLocale("flow.cancelled"))
            return true
        }
        if (replyTo.messageId() == state.lastPromptMessageId) {
            handleSheetIdStep(context.chatId, context.msg, context.text, state)
            return true
        }
        return false
    }

    override fun matchesStartTrigger(context: MessageContext): Boolean =
        context.replyTo == null &&
            context.text.trim().equals(createHouseholdTrigger, ignoreCase = true)

    override fun start(context: MessageContext) {
        if (householdRepository.findUserByChatId(context.chatId) != null) {
            reply(context.msg, applyLocale("flow.alreadyInHousehold"))
            return
        }
        val promptId = reply(
            context.msg,
            applyLocale("createHousehold.start", cancelTrigger, authentication.serviceAccountEmail),
        ) ?: return
        states[context.chatId] = State.AwaitingSheetId(promptId)
    }

    override fun tryHandleCallback(context: CallbackContext): Boolean = false

    private fun handleSheetIdStep(chatId: Long, msg: Message, rawText: String, state: State) {
        val text = rawText.trim()
        when (state) {
            is State.AwaitingSheetId -> {
                if (text.isEmpty()) {
                    val promptId = reply(msg, applyLocale("createHousehold.emptySheetId")) ?: return
                    states[chatId] = State.AwaitingSheetId(promptId)
                    return
                }
                val sheetId = extractSheetId(text)
                val result = try {
                    createHouseholdUseCase.create(chatId, sheetId)
                } catch (e: Exception) {
                    println("Failed to create household for chat $chatId: ${e.message}")
                    states.remove(chatId)
                    reply(msg, applyLocale("createHousehold.failed", e.message ?: ""))
                    return
                }
                states.remove(chatId)
                when (result) {
                    is CreateHouseholdUseCase.Result.Created -> reply(
                        msg,
                        applyLocale("createHousehold.success", addCardTrigger, authentication.serviceAccountEmail),
                    )
                    CreateHouseholdUseCase.Result.AlreadyInHousehold -> reply(msg, applyLocale("flow.alreadyInHousehold"))
                }
            }
        }
    }

    private fun extractSheetId(input: String): String =
        SHEETS_URL_PATTERN.find(input)?.groupValues?.get(1) ?: input.trim()

    private fun reply(msg: Message, text: String): Int? =
        gateway.send(msg.chat().id(), text, replyToMessageId = msg.messageId())?.toInt()

    private fun applyLocale(code: String, vararg args: Any?): String {
        val stringArgs: Array<Any?> = Array(args.size) { args[it]?.toString() }
        return messageSource.getMessage(code, stringArgs, Locale.ROOT)
    }

    private sealed class State {
        abstract val lastPromptMessageId: Int
        data class AwaitingSheetId(override val lastPromptMessageId: Int) : State()
    }

    private companion object {
        // Accept either a full Google Sheets URL ("https://docs.google.com/spreadsheets/d/{ID}/edit…")
        // or the raw ID. If the pattern matches, use the captured ID; otherwise treat the trimmed
        // input as the ID itself.
        private val SHEETS_URL_PATTERN: Regex =
            Regex("""docs\.google\.com/spreadsheets/d/([a-zA-Z0-9_-]+)""")
    }
}

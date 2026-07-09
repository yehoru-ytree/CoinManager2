package MailAggregator.MailAggregator.telegram.wizard

import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.common.usecases.AddCategoryUseCase
import MailAggregator.MailAggregator.household.Household
import MailAggregator.MailAggregator.household.repository.HouseholdRepository
import MailAggregator.MailAggregator.telegram.TelegramGateway
import com.pengrad.telegrambot.model.Message
import org.springframework.context.MessageSource
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AddCategoryWizard(
    private val gateway: TelegramGateway,
    private val categoryRepository: CategoryRepository,
    private val addCategoryUseCase: AddCategoryUseCase,
    private val householdRepository: HouseholdRepository,
    private val messageSource: MessageSource,
) : Wizard {

    private val states = ConcurrentHashMap<Long, State>()

    private val addCategoryTrigger: String by lazy { translate("trigger.addCategory") }
    private val cancelTrigger: String by lazy { translate("trigger.cancel") }
    private val emptyKeywordsTrigger: String by lazy { translate("trigger.emptyKeywords") }
    private val namePattern: Regex by lazy { Regex(translate("validation.namePattern")) }
    private val priorityMin: Int by lazy { translate("validation.priority.min").toInt() }
    private val priorityMax: Int by lazy { translate("validation.priority.max").toInt() }

    override fun hasState(chatId: Long): Boolean = states.containsKey(chatId)

    override fun resetState(chatId: Long): Boolean = states.remove(chatId) != null

    override fun tryHandleMidFlow(context: MessageContext): Boolean {
        val state = states[context.chatId] ?: return false
        val replyTo = context.replyTo ?: return false

        if (replyTo.from()?.isBot == true && context.text.trim().equals(cancelTrigger, ignoreCase = true)) {
            states.remove(context.chatId)
            reply(context.msg, "Окей, забил.")
            return true
        }
        if (replyTo.messageId() == state.lastPromptMessageId) {
            val household = context.household ?: return true
            handleStep(context.chatId, context.msg, context.text.trim(), state, household)
            return true
        }
        return false
    }

    override fun matchesStartTrigger(context: MessageContext): Boolean =
        context.user != null &&
            context.replyTo == null &&
            context.text.trim().equals(addCategoryTrigger, ignoreCase = true)

    override fun start(context: MessageContext) {
        val promptId = reply(context.msg, translate("addCategory.start", cancelTrigger)) ?: return
        states[context.chatId] = State.AwaitingName(promptId)
    }

    override fun tryHandleCallback(context: CallbackContext): Boolean = false

    private fun handleStep(chatId: Long, msg: Message, text: String, state: State, household: Household) {
        when (state) {
            is State.AwaitingName -> handleNameStep(chatId, msg, text, household)
            is State.AwaitingDisplayName -> handleDisplayNameStep(chatId, msg, text, state)
            is State.AwaitingPriority -> handlePriorityStep(chatId, msg, text, state)
            is State.AwaitingKeywords -> handleKeywordsStep(chatId, msg, text, state, household)
        }
    }

    private fun handleNameStep(chatId: Long, msg: Message, name: String, household: Household) {
        if (!name.matches(namePattern)) {
            val promptId = reply(msg, translate("addCategory.name.bad")) ?: return
            states[chatId] = State.AwaitingName(promptId)
            return
        }
        if (categoryRepository.findByName(household.id, name) != null) {
            val promptId = reply(msg, translate("addCategory.name.exists", name)) ?: return
            states[chatId] = State.AwaitingName(promptId)
            return
        }
        val promptId = reply(msg, translate("addCategory.displayName.prompt")) ?: return
        states[chatId] = State.AwaitingDisplayName(promptId, name)
    }

    private fun handleDisplayNameStep(chatId: Long, msg: Message, displayName: String, prev: State.AwaitingDisplayName) {
        if (displayName.isEmpty()) {
            val promptId = reply(msg, translate("addCategory.displayName.empty")) ?: return
            states[chatId] = prev.copy(lastPromptMessageId = promptId)
            return
        }
        val promptId = reply(msg, translate("addCategory.priority.prompt", priorityMin, priorityMax)) ?: return
        states[chatId] = State.AwaitingPriority(promptId, prev.name, displayName)
    }

    private fun handlePriorityStep(chatId: Long, msg: Message, priorityRaw: String, prev: State.AwaitingPriority) {
        val priority = priorityRaw.toIntOrNull()
        if (priority == null || priority !in priorityMin..priorityMax) {
            val promptId = reply(msg, translate("addCategory.priority.bad", priorityMin, priorityMax)) ?: return
            states[chatId] = prev.copy(lastPromptMessageId = promptId)
            return
        }
        val promptId = reply(msg, translate("addCategory.keywords.prompt", emptyKeywordsTrigger)) ?: return
        states[chatId] = State.AwaitingKeywords(promptId, prev.name, prev.displayName, priority)
    }

    private fun handleKeywordsStep(
        chatId: Long,
        msg: Message,
        keywordsRaw: String,
        prev: State.AwaitingKeywords,
        household: Household,
    ) {
        val keywords = if (keywordsRaw == emptyKeywordsTrigger) {
            emptyList()
        } else {
            keywordsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        val category = try {
            addCategoryUseCase.add(
                household = household,
                name = prev.name,
                displayName = prev.displayName,
                priority = prev.priority,
                keywords = keywords,
            )
        } catch (e: Exception) {
            println("Failed to add category ${prev.name}: ${e.message}")
            states.remove(chatId)
            reply(msg, translate("addCategory.createFailed", e.message ?: ""))
            return
        }
        states.remove(chatId)
        reply(msg, translate("addCategory.created", category.name, category.displayName, category.sheetRow))
        broadcastInfo(
            household.id,
            excludeChatId = chatId,
            text = translate("addCategory.broadcast", displayNameOf(msg), category.displayName),
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

    private fun displayNameOf(msg: Message): String {
        val user = msg.from()
        return user?.firstName()?.takeIf { it.isNotBlank() }
            ?: user?.username()?.takeIf { it.isNotBlank() }
            ?: translate("displayName.unknown")
    }

    private fun reply(msg: Message, text: String): Int? =
        gateway.send(msg.chat().id(), text, replyToMessageId = msg.messageId())?.toInt()

    private fun translate(code: String, vararg args: Any?): String {
        val stringArgs: Array<Any?> = Array(args.size) { args[it]?.toString() }
        return messageSource.getMessage(code, stringArgs, Locale.ROOT)
    }

    private sealed class State {
        abstract val lastPromptMessageId: Int

        data class AwaitingName(override val lastPromptMessageId: Int) : State()
        data class AwaitingDisplayName(
            override val lastPromptMessageId: Int,
            val name: String,
        ) : State()
        data class AwaitingPriority(
            override val lastPromptMessageId: Int,
            val name: String,
            val displayName: String,
        ) : State()
        data class AwaitingKeywords(
            override val lastPromptMessageId: Int,
            val name: String,
            val displayName: String,
            val priority: Int,
        ) : State()
    }
}

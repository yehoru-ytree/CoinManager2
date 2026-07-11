package MailAggregator.MailAggregator.telegram.wizard

import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.common.usecases.RemoveCategoryUseCase
import MailAggregator.MailAggregator.household.repository.HouseholdRepository
import MailAggregator.MailAggregator.telegram.TelegramGateway
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import org.springframework.context.MessageSource
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Two-step delete flow.
 *
 * 1. User types "Удалить категорию" → picker keyboard of removable categories
 *    (ACTIVE + not isDefault + not isOther).
 * 2. User taps a category → confirmation keyboard.
 * 3. User taps "Да, удалить" → [RemoveCategoryUseCase] runs; broadcast to other members.
 *
 * Callback prefixes:
 *   - `rc|<categoryId>`  — pick a category from the initial picker.
 *   - `rcc|<categoryId>` — confirm removal for the previously-picked category.
 */
class RemoveCategoryWizard(
    private val gateway: TelegramGateway,
    private val categoryRepository: CategoryRepository,
    private val removeCategoryUseCase: RemoveCategoryUseCase,
    private val householdRepository: HouseholdRepository,
    private val messageSource: MessageSource,
) : Wizard {

    private val states = ConcurrentHashMap<Long, State>()

    private val removeCategoryTrigger: String by lazy { applyLocale("trigger.removeCategory") }
    private val cancelTrigger: String by lazy { applyLocale("trigger.cancel") }

    override fun hasState(chatId: Long): Boolean = states.containsKey(chatId)

    override fun resetState(chatId: Long): Boolean = states.remove(chatId) != null

    override fun tryHandleMidFlow(context: MessageContext): Boolean {
        val state = states[context.chatId] ?: return false
        val replyTo = context.replyTo ?: return false

        if (replyTo.from()?.isBot == true && context.text.trim().equals(cancelTrigger, ignoreCase = true)) {
            states.remove(context.chatId)
            reply(context.msg, applyLocale("flow.cancelled"))
            return true
        }
        // The wizard is entirely callback-driven; a stray text reply on the picker prompt is ignored.
        // Return true (consumed) only if the reply is threaded under our current prompt to stop other
        // wizards from picking it up.
        return replyTo.messageId() == state.lastPromptMessageId
    }

    override fun matchesStartTrigger(context: MessageContext): Boolean =
        context.user != null &&
            context.replyTo == null &&
            context.text.trim().equals(removeCategoryTrigger, ignoreCase = true)

    override fun start(context: MessageContext) {
        val household = context.household ?: return
        val removable = categoryRepository.findAll(household.id)
            .filter { !it.isOther }
            .sortedBy { it.sheetRow }
        if (removable.isEmpty()) {
            reply(context.msg, applyLocale("removeCategory.noneRemovable"))
            return
        }
        val keyboard = pickerKeyboard(removable)
        val promptId = gateway.send(
            context.msg.chat().id(),
            applyLocale("removeCategory.pick", cancelTrigger),
            keyboard = keyboard,
            replyToMessageId = context.msg.messageId(),
        ) ?: return
        states[context.chatId] = State.AwaitingPick(promptId.toInt())
    }

    override fun tryHandleCallback(context: CallbackContext): Boolean {
        val prefix = context.data.substringBefore('|')
        return when (prefix) {
            "rc" -> { handlePickCallback(context); true }
            "rcc" -> { handleConfirmCallback(context); true }
            else -> false
        }
    }

    private fun handlePickCallback(context: CallbackContext) {
        val state = states[context.chatId] as? State.AwaitingPick
        val pickerMsg = context.cq.message()
        if (state == null || pickerMsg == null || pickerMsg.messageId() != state.lastPromptMessageId) {
            gateway.answerCallback(context.cq.id(), applyLocale("callback.alreadyDone"))
            return
        }
        val categoryId = context.data.substringAfter('|', missingDelimiterValue = "").let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }
        if (categoryId == null) {
            gateway.answerCallback(context.cq.id(), applyLocale("callback.badCallback"))
            return
        }
        val category = categoryRepository.findById(categoryId)
        if (category == null || category.householdId != context.user.householdId) {
            gateway.answerCallback(context.cq.id(), applyLocale("callback.badCallback"))
            return
        }

        gateway.editKeyboard(context.chatId, pickerMsg.messageId().toLong())
        gateway.answerCallback(context.cq.id())

        val keyboard = confirmationKeyboard(category)
        val promptId = gateway.send(
            context.chatId,
            applyLocale("removeCategory.confirm", category.displayName),
            keyboard = keyboard,
            replyToMessageId = pickerMsg.messageId(),
        ) ?: return
        states[context.chatId] = State.AwaitingConfirmation(promptId.toInt(), categoryId)
    }

    private fun handleConfirmCallback(context: CallbackContext) {
        val state = states[context.chatId] as? State.AwaitingConfirmation
        val confirmMsg = context.cq.message()
        if (state == null || confirmMsg == null || confirmMsg.messageId() != state.lastPromptMessageId) {
            gateway.answerCallback(context.cq.id(), applyLocale("callback.alreadyDone"))
            return
        }
        // The confirmation button carries the category id — must match the state's captured id
        // to protect against a stale button being tapped after wizard restart.
        val callbackCategoryId = context.data.substringAfter('|', missingDelimiterValue = "").let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }
        if (callbackCategoryId != state.categoryId) {
            gateway.answerCallback(context.cq.id(), applyLocale("callback.alreadyDone"))
            return
        }
        val household = householdRepository.findHousehold(context.user.householdId)
        if (household == null) {
            gateway.answerCallback(context.cq.id(), applyLocale("callback.badCallback"))
            return
        }

        gateway.editKeyboard(context.chatId, confirmMsg.messageId().toLong())
        gateway.answerCallback(context.cq.id())

        val result = removeCategoryUseCase.remove(household, state.categoryId)
        states.remove(context.chatId)

        when (result) {
            is RemoveCategoryUseCase.Result.Removed -> {
                gateway.send(context.chatId, applyLocale("removeCategory.done", result.category.displayName))
                broadcastInfo(
                    household.id,
                    excludeChatId = context.chatId,
                    text = applyLocale("removeCategory.broadcast", displayNameOf(context.cq.from()), result.category.displayName),
                )
            }
            is RemoveCategoryUseCase.Result.CannotRemoveOther ->
                gateway.send(context.chatId, applyLocale("removeCategory.cannotRemoveOther"))
            is RemoveCategoryUseCase.Result.AlreadyRemoved ->
                gateway.send(context.chatId, applyLocale("removeCategory.alreadyRemoved", result.category.displayName))
            RemoveCategoryUseCase.Result.NotFound ->
                gateway.send(context.chatId, applyLocale("callback.badCallback"))
        }
    }

    private fun pickerKeyboard(categories: List<Category>): InlineKeyboardMarkup {
        val rows = categories.chunked(3).map { chunk ->
            chunk.map { category ->
                InlineKeyboardButton(category.displayName).callbackData("rc|${category.id}")
            }.toTypedArray()
        }
        return InlineKeyboardMarkup(*rows.toTypedArray())
    }

    private fun confirmationKeyboard(category: Category): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            arrayOf(
                InlineKeyboardButton(applyLocale("removeCategory.button.confirm")).callbackData("rcc|${category.id}"),
            ),
        )

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

    private fun displayNameOf(tgUser: com.pengrad.telegrambot.model.User?): String =
        tgUser?.firstName()?.takeIf { it.isNotBlank() }
            ?: tgUser?.username()?.takeIf { it.isNotBlank() }
            ?: applyLocale("displayName.unknown")

    private fun reply(msg: Message, text: String): Int? =
        gateway.send(msg.chat().id(), text, replyToMessageId = msg.messageId())?.toInt()

    private fun applyLocale(code: String, vararg args: Any?): String {
        val stringArgs: Array<Any?> = Array(args.size) { args[it]?.toString() }
        return messageSource.getMessage(code, stringArgs, Locale.ROOT)
    }

    private sealed class State {
        abstract val lastPromptMessageId: Int

        data class AwaitingPick(override val lastPromptMessageId: Int) : State()
        data class AwaitingConfirmation(
            override val lastPromptMessageId: Int,
            val categoryId: UUID,
        ) : State()
    }
}

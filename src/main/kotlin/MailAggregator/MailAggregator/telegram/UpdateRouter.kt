package MailAggregator.MailAggregator.telegram

import MailAggregator.MailAggregator.household.repository.HouseholdRepository
import MailAggregator.MailAggregator.telegram.wizard.CallbackContext
import MailAggregator.MailAggregator.telegram.wizard.MessageContext
import MailAggregator.MailAggregator.telegram.wizard.Wizard
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Update
import jakarta.annotation.PostConstruct
import org.springframework.context.MessageSource
import java.util.Locale

/**
 * Top-level dispatcher for every incoming [Update].
 *
 * Two priority tiers of [Wizard]s:
 *  - [publicWizards] run before the registered-user gate (e.g. CreateHousehold, which is how a
 *    fresh chat gets bootstrapped into a household).
 *  - [registeredWizards] run after the gate — [PlainCommandHandler.handleUnregisteredMessage]
 *    absorbs unregistered chats before this tier is tried.
 *
 * Each phase asks wizards in list order — first `tryHandleMidFlow`, then `matchesStartTrigger`
 * + [resetOtherFlows] + `start`. Callback queries are offered to all registered wizards
 * (regardless of `hasState`), then to [PlainCommandHandler.tryHandleCallback]; unmatched
 * callbacks get the `callback.badCallback` toast.
 */
class UpdateRouter(
    private val gateway: TelegramGateway,
    private val householdRepository: HouseholdRepository,
    private val plainCommands: PlainCommandHandler,
    private val publicWizards: List<Wizard>,
    private val registeredWizards: List<Wizard>,
    private val messageSource: MessageSource,
) {
    private val allWizards: List<Wizard> = publicWizards + registeredWizards

    /** Register [handleUpdate] as the long-polling callback. Called by Spring after all
     *  wizards + PlainCommandHandler are wired. */
    @PostConstruct
    fun startLongPolling() {
        gateway.start(::handleUpdate)
    }

    fun handleUpdate(update: Update) {
        val msg = update.message()
        if (msg != null && msg.text() != null) {
            handleTextMessage(msg)
            return
        }
        val cq = update.callbackQuery() ?: return
        handleCallback(cq)
    }

    private fun handleTextMessage(msg: Message) {
        val chatId = msg.chat().id()
        val text = msg.text()
        val replyTo = msg.replyToMessage()

        if (text == "/start") {
            plainCommands.sendStartGreeting(chatId)
            return
        }

        // Public tier — no user/household needed.
        val publicContext = MessageContext(chatId, msg, text, replyTo, user = null, household = null)
        for (wizard in publicWizards) {
            if (wizard.tryHandleMidFlow(publicContext)) return
        }
        for (wizard in publicWizards) {
            if (wizard.matchesStartTrigger(publicContext)) {
                resetOtherFlows(chatId, msg, currentlyStarting = wizard)
                wizard.start(publicContext)
                return
            }
        }
        if (plainCommands.matchesJoinTrigger(text, replyTo)) {
            plainCommands.handleJoinCommand(chatId, msg, text)
            return
        }

        // Registration gate.
        val user = householdRepository.findUserByChatId(chatId)
        if (user == null) {
            plainCommands.handleUnregisteredMessage(msg, text, replyTo)
            return
        }
        val household = householdRepository.findHousehold(user.householdId) ?: return

        // Registered tier.
        val registeredContext = MessageContext(chatId, msg, text, replyTo, user = user, household = household)
        for (wizard in registeredWizards) {
            if (wizard.tryHandleMidFlow(registeredContext)) return
        }
        for (wizard in registeredWizards) {
            if (wizard.matchesStartTrigger(registeredContext)) {
                resetOtherFlows(chatId, msg, currentlyStarting = wizard)
                wizard.start(registeredContext)
                return
            }
        }

        // Remaining stateless registered-user paths (invite, comment reply, help, unknown).
        if (plainCommands.matchesInviteTrigger(text, replyTo)) {
            plainCommands.handleInviteCommand(msg, user)
            return
        }
        if (plainCommands.isReplyToBotMessage(replyTo)) {
            plainCommands.handleCommentReply(msg, replyTo!!.messageId().toLong(), text)
            return
        }
        if (plainCommands.matchesHelpTrigger(text, replyTo)) {
            plainCommands.sendHelp(msg)
            return
        }
        if (replyTo == null) {
            plainCommands.sendUnknown(msg)
        }
    }

    private fun handleCallback(cq: com.pengrad.telegrambot.model.CallbackQuery) {
        val chatId = cq.message()?.chat()?.id() ?: return
        val user = householdRepository.findUserByChatId(chatId)
        if (user == null) {
            gateway.answerCallback(cq.id(), translate("callback.notAllowed"))
            return
        }
        val data = cq.data() ?: return
        val context = CallbackContext(chatId = chatId, cq = cq, data = data, user = user)
        for (wizard in allWizards) {
            if (wizard.tryHandleCallback(context)) return
        }
        if (plainCommands.tryHandleCallback(cq, chatId, user, data)) return
        gateway.answerCallback(cq.id(), translate("callback.badCallback"))
    }

    /**
     * When a fresh start-trigger fires, drop any in-progress flow so the user isn't trapped with a
     * half-finished wizard they forgot about. The notice text differs by whether the wizard being
     * started was already active ("restarting") vs a different one ("dropped, starting new").
     */
    private fun resetOtherFlows(chatId: Long, msg: Message, currentlyStarting: Wizard) {
        val restartingSelf = currentlyStarting.hasState(chatId)
        val hadAnyFlow = allWizards.fold(false) { acc, wizard -> wizard.resetState(chatId) || acc }
        val notice = when {
            hadAnyFlow && restartingSelf -> translate("flow.restart.startingNew")
            hadAnyFlow -> translate("flow.restart.continue")
            else -> return
        }
        gateway.send(msg.chat().id(), notice, replyToMessageId = msg.messageId())
    }

    private fun translate(code: String, vararg args: Any?): String {
        val stringArgs: Array<Any?> = Array(args.size) { args[it]?.toString() }
        return messageSource.getMessage(code, stringArgs, Locale.ROOT)
    }
}

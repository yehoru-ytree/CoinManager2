package MailAggregator.MailAggregator.telegram

import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.common.config.Config.Companion.TIME_ZONE
import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.common.usecases.AddCashTransactionUseCase
import MailAggregator.MailAggregator.common.usecases.AddCategoryUseCase
import MailAggregator.MailAggregator.common.usecases.HandleTelegramCommentUseCase
import MailAggregator.MailAggregator.common.usecases.SaveKeywordUseCase
import MailAggregator.MailAggregator.household.BotUser
import MailAggregator.MailAggregator.household.Household
import MailAggregator.MailAggregator.household.repository.HouseholdRepository
import MailAggregator.MailAggregator.household.repository.InviteTokenRepository
import MailAggregator.MailAggregator.household.usecase.AddBankAccountUseCase
import MailAggregator.MailAggregator.household.usecase.CreateHouseholdUseCase
import MailAggregator.MailAggregator.household.usecase.JoinHouseholdUseCase
import MailAggregator.MailAggregator.bank.Transaction
import MailAggregator.MailAggregator.bank.TransactionStatus
import MailAggregator.MailAggregator.bank.repository.TransactionRepository
import MailAggregator.MailAggregator.bank.repository.TransactionStatusRepository
import MailAggregator.MailAggregator.monobank.api.MonobankApi
import MailAggregator.MailAggregator.spreadsheet.Authentication
import MailAggregator.MailAggregator.telegram.model.CategorizationRequest
import MailAggregator.MailAggregator.telegram.repository.TelegramLogMessageRepository
import com.pengrad.telegrambot.model.CallbackQuery
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import jakarta.annotation.PostConstruct
import org.springframework.context.MessageSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

class CategorizationBot(
    private val gateway: TelegramGateway,
    private val categoryRepository: CategoryRepository,
    private val addCategoryUseCase: AddCategoryUseCase,
    private val transactionRepository: TransactionRepository,
    private val telegramLogMessageRepository: TelegramLogMessageRepository,
    private val handleTelegramCommentUseCase: HandleTelegramCommentUseCase,
    private val saveKeywordUseCase: SaveKeywordUseCase,
    private val transactionStatusRepository: TransactionStatusRepository,
    private val householdRepository: HouseholdRepository,
    private val createHouseholdUseCase: CreateHouseholdUseCase,
    private val joinHouseholdUseCase: JoinHouseholdUseCase,
    private val addBankAccountUseCase: AddBankAccountUseCase,
    private val addCashTransactionUseCase: AddCashTransactionUseCase,
    private val inviteTokenRepository: InviteTokenRepository,
    private val authentication: Authentication,
    private val monobankApi: MonobankApi,
    private val ingestEmail: String,
    private val messageSource: MessageSource,
    private val zoneId: ZoneId = TIME_ZONE,
    private val onDecision: (txId: String, decision: Decision) -> Unit,
) {
    // Extracted wizards — each owns its own state map + step handlers. Router logic in
    // handleUpdate delegates its mid-flow, start and callback branches to these classes.
    private val cashEntryWizard = MailAggregator.MailAggregator.telegram.wizard.CashEntryWizard(
        gateway = gateway,
        addCashTransactionUseCase = addCashTransactionUseCase,
        messageSource = messageSource,
        zoneId = zoneId,
        broadcastTx = ::sendTx,
    )
    private val createHouseholdWizard = MailAggregator.MailAggregator.telegram.wizard.CreateHouseholdWizard(
        gateway = gateway,
        householdRepository = householdRepository,
        createHouseholdUseCase = createHouseholdUseCase,
        authentication = authentication,
        messageSource = messageSource,
    )
    private val addCategoryWizard = MailAggregator.MailAggregator.telegram.wizard.AddCategoryWizard(
        gateway = gateway,
        categoryRepository = categoryRepository,
        addCategoryUseCase = addCategoryUseCase,
        householdRepository = householdRepository,
        messageSource = messageSource,
    )
    private val addCardWizard = MailAggregator.MailAggregator.telegram.wizard.AddCardWizard(
        gateway = gateway,
        addBankAccountUseCase = addBankAccountUseCase,
        monobankApi = monobankApi,
        householdRepository = householdRepository,
        messageSource = messageSource,
        ingestEmail = ingestEmail,
    )

    // Input-matching values loaded once from messages.properties (lazy so they read the bundle
    // after Spring has finished wiring `messageSource`, not during property initialization).
    private val saveKeywordTrigger: String by lazy { t("trigger.save") }
    private val addCategoryTrigger: String by lazy { t("trigger.addCategory") }
    private val createHouseholdTrigger: String by lazy { t("trigger.createHousehold") }
    private val addCardTrigger: String by lazy { t("trigger.addCard") }
    private val cashTrigger: String by lazy { t("trigger.cash") }
    private val inviteTrigger: String by lazy { t("trigger.invite") }
    private val joinTrigger: String by lazy { t("trigger.join") }
    private val cancelTrigger: String by lazy { t("trigger.cancel") }
    private val helpTriggers: Set<String> by lazy {
        t("trigger.help").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    @PostConstruct
    fun startLongPolling() {
        gateway.start(::handleUpdate)
    }

    // Plain DM to a single chat — used by the email ingestor to relay Gmail forwarding
    // verification codes to the user without going through the keyword/category pipeline.
    fun notifyChat(chatId: Long, text: String) {
        gateway.send(chatId, text)
    }

    fun sendTx(transaction: CategorizationRequest) {
        val text = t(
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
        val tail = category?.let { t("log.tail.category", it.displayName) } ?: t("log.tail.ignored")

        val text = buildString {
            appendLine(t("log.title", transaction.description))
            appendLine(t("log.body", date, time, amount, currency))
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

    private fun handleUpdate(update: Update) {
        val msg = update.message()

        if (msg != null && msg.text() != null) {
            val chatId = msg.chat().id()
            val text = msg.text()
            val replyTo = msg.replyToMessage()

            if (text == "/start") {
                gateway.send(chatId, t("bot.start.greeting", chatId))
                return
            }

            // ===== Public commands (work for unregistered chats too) =====

            val publicContext = MailAggregator.MailAggregator.telegram.wizard.MessageContext(
                chatId = chatId, msg = msg, text = text, replyTo = replyTo,
                user = null, household = null, // resolved below only for registered-user flows
            )
            if (createHouseholdWizard.tryHandleMidFlow(publicContext)) return
            if (createHouseholdWizard.matchesStartTrigger(publicContext)) {
                resetAllFlows(chatId, msg, restarting = createHouseholdWizard.hasState(chatId))
                createHouseholdWizard.start(publicContext)
                return
            }
            if (replyTo == null && text.trim().startsWith(joinTrigger, ignoreCase = true)) {
                handleJoinCommand(chatId, msg, text)
                return
            }

            // ===== From here on, the chat must belong to a registered user =====

            val user = householdRepository.findUserByChatId(chatId)
            if (user == null) {
                if (replyTo == null && text.trim().lowercase() in helpTriggers) {
                    sendHelp(msg)
                    return
                }
                if (replyTo == null) {
                    reply(msg, t("bot.notRegistered", createHouseholdTrigger, joinTrigger))
                }
                return
            }
            val household = householdRepository.findHousehold(user.householdId) ?: return

            // ===== Mid-flow handlers for registered users =====

            val wizardContext = MailAggregator.MailAggregator.telegram.wizard.MessageContext(
                chatId = chatId, msg = msg, text = text, replyTo = replyTo, user = user, household = household,
            )
            if (addCategoryWizard.tryHandleMidFlow(wizardContext)) return
            if (addCardWizard.tryHandleMidFlow(wizardContext)) return
            if (cashEntryWizard.tryHandleMidFlow(wizardContext)) return

            // ===== Plain-message triggers for registered users =====

            if (addCategoryWizard.matchesStartTrigger(wizardContext)) {
                resetAllFlows(chatId, msg, restarting = addCategoryWizard.hasState(chatId))
                addCategoryWizard.start(wizardContext)
                return
            }
            if (addCardWizard.matchesStartTrigger(wizardContext)) {
                resetAllFlows(chatId, msg, restarting = addCardWizard.hasState(chatId))
                addCardWizard.start(wizardContext)
                return
            }
            if (cashEntryWizard.matchesStartTrigger(wizardContext)) {
                resetAllFlows(chatId, msg, restarting = cashEntryWizard.hasState(chatId))
                cashEntryWizard.start(wizardContext)
                return
            }
            if (replyTo == null && text.trim().equals(inviteTrigger, ignoreCase = true)) {
                handleInviteCommand(msg, user)
                return
            }

            if (replyTo != null && replyTo.from()?.isBot == true) {
                handleCommentReply(msg, replyTo.messageId().toLong(), text)
                return
            }

            if (replyTo == null && text.trim().lowercase() in helpTriggers) {
                sendHelp(msg)
                return
            }

            if (replyTo == null) {
                reply(msg, t("bot.unknown"))
                return
            }
            return
        }

        val cq = update.callbackQuery() ?: return
        val chatId = cq.message()?.chat()?.id() ?: return
        val user = householdRepository.findUserByChatId(chatId)
        if (user == null) {
            gateway.answerCallback(cq.id(), t("callback.notAllowed"))
            return
        }

        val data = cq.data() ?: return
        val callbackContext = MailAggregator.MailAggregator.telegram.wizard.CallbackContext(
            chatId = chatId, cq = cq, data = data, user = user,
        )
        if (addCardWizard.tryHandleCallback(callbackContext)) return

        when (data.firstOrNull()) {
            'c' -> handleCategorizationCallback(cq, chatId, user, data)
            'k' -> handleSaveKeywordCallback(cq, chatId, user, data)
            else -> gateway.answerCallback(cq.id(), t("callback.badCallback"))
        }
    }

    /** Drop any active multi-step flow for this chat. Used when a new plain trigger is received
     *  so the user doesn't end up trapped in a half-finished flow they forgot about. */
    private fun resetAllFlows(chatId: Long, msg: Message, restarting: Boolean) {
        val hadFlow = addCategoryWizard.resetState(chatId) ||
            createHouseholdWizard.resetState(chatId) ||
            addCardWizard.resetState(chatId) ||
            cashEntryWizard.resetState(chatId)
        if (hadFlow && restarting) {
            reply(msg, t("flow.restart.startingNew"))
        } else if (hadFlow) {
            reply(msg, t("flow.restart.continue"))
        }
    }


    // ----- Invite / Join -----

    private fun handleInviteCommand(msg: Message, user: BotUser) {
        val token = inviteTokenRepository.create(user.householdId)
        reply(msg, t("invite.result", token, joinTrigger))
    }

    private fun handleJoinCommand(chatId: Long, msg: Message, text: String) {
        val token = text.trim().removePrefix(joinTrigger).trim()
        if (token.isEmpty()) {
            reply(msg, t("join.usage", joinTrigger))
            return
        }
        val result = joinHouseholdUseCase.join(chatId, token)
        when (result) {
            is JoinHouseholdUseCase.Result.Joined -> reply(msg, t("join.success", addCardTrigger))
            JoinHouseholdUseCase.Result.AlreadyInHousehold -> reply(msg, t("join.alreadyJoined"))
            JoinHouseholdUseCase.Result.InvalidToken -> reply(msg, t("join.invalidToken"))
        }
    }

    // ----- Existing categorization callbacks -----

    private fun handleCategorizationCallback(
        cq: com.pengrad.telegrambot.model.CallbackQuery,
        chatId: Long,
        user: BotUser,
        data: String,
    ) {
        val parsed = parseCallbackData(user.householdId, data) ?: run {
            gateway.answerCallback(cq.id(), t("callback.badCallback"))
            return
        }

        // First-wins: if another household member already decided on this transaction, this tap
        // is a no-op — the decision is final. Strip this user's stale keyboard so they can't tap
        // again and answer the callback with a friendly toast.
        val existingStatus = transactionStatusRepository.findByTransactionId(parsed.txId)
        if (existingStatus == TransactionStatus.EXECUTED || existingStatus == TransactionStatus.IGNORED) {
            val message = cq.message()
            if (message != null) {
                gateway.editKeyboard(chatId, message.messageId().toLong())
            }
            gateway.answerCallback(cq.id(), t("callback.alreadyDone"))
            return
        }

        onDecision(parsed.txId, parsed.decision)

        // Remove keyboards from ALL household members' copies of this prompt, not just the one
        // who tapped. Otherwise other members still see a live keyboard pointing at a transaction
        // that's already been merged into the sheet → tapping it would double-count.
        clearKeyboardsForTx(parsed.txId)

        gateway.answerCallback(cq.id(), t("callback.saved"))
        sendLogForDecision(parsed.txId, parsed.decision)
    }

    private fun clearKeyboardsForTx(txId: String) {
        val prompts = telegramLogMessageRepository.findAllByTransactionId(txId)
        for (prompt in prompts) {
            try {
                gateway.editKeyboard(prompt.chatId, prompt.messageId)
            } catch (e: Exception) {
                // Telegram returns 400 «message is not modified» when the keyboard is already gone,
                // or «message to edit not found» if the user deleted their copy. Both are fine.
                println("Could not clear keyboard on chat=${prompt.chatId} msg=${prompt.messageId}: ${e.message}")
            }
        }
    }

    private fun handleSaveKeywordCallback(
        cq: com.pengrad.telegrambot.model.CallbackQuery,
        chatId: Long,
        user: BotUser,
        data: String,
    ) {
        val parsed = parseSaveKeywordCallback(user.householdId, data) ?: run {
            gateway.answerCallback(cq.id(), t("callback.badCallback"))
            return
        }
        val tx = transactionRepository.get(parsed.txId).orElse(null)
        if (tx == null) {
            gateway.answerCallback(cq.id(), t("callback.txNotFound"))
            return
        }
        val result = saveKeywordUseCase(parsed.categoryId, tx.description)
        val message = cq.message()
        if (message != null) {
            gateway.editKeyboard(chatId, message.messageId().toLong())
        }
        val (callbackText, replyText) = when (result) {
            is SaveKeywordUseCase.Result.Saved ->
                t("callback.saved") to t("savekw.success", result.keyword, result.category.displayName)
            is SaveKeywordUseCase.Result.AlreadyPresent ->
                t("callback.alreadyPresent") to t("savekw.alreadyPresent", result.keyword, result.category.displayName)
            SaveKeywordUseCase.Result.CategoryNotFound ->
                t("callback.categoryNotFound") to t("savekw.categoryNotFound")
            SaveKeywordUseCase.Result.EmptyKeyword ->
                t("callback.empty") to t("savekw.empty")
        }
        gateway.answerCallback(cq.id(), callbackText)
        gateway.send(chatId, replyText)
    }

    // ----- Reply handling (comment / Сохранить) -----

    private fun handleCommentReply(msg: Message, replyToMessageId: Long, text: String) {
        if (text.isBlank()) return

        if (text.trim().equals(saveKeywordTrigger, ignoreCase = true)) {
            promptSaveKeywordCategory(msg.chat().id(), replyToMessageId)
            return
        }

        val saved = try {
            handleTelegramCommentUseCase(msg.chat().id(), replyToMessageId, text)
        } catch (e: Exception) {
            println("Failed to save Telegram comment: ${e.message}")
            reply(msg, t("comment.saveError"))
            return
        }
        // Thread the ack under the user's actual comment so the chat keeps a clean
        // «commented → ✓ saved» visual chain.
        reply(msg, if (saved) t("comment.saved") else t("comment.notFound"))
    }

    private fun promptSaveKeywordCategory(chatId: Long, replyToMessageId: Long) {
        val record = telegramLogMessageRepository.findByChatAndMessage(chatId, replyToMessageId)
        if (record == null) {
            gateway.send(chatId, t("savekw.txMissing"))
            return
        }
        val tx = transactionRepository.get(record.transactionId).orElse(null)
        if (tx == null) {
            gateway.send(chatId, t("savekw.txDbMissing"))
            return
        }
        if (tx.isCash) {
            // Cash entries all share description «Наличка» — saving it as a keyword would
            // pollute the category's regex list without ever matching anything useful (cash flow
            // bypasses CategorizeExpenseUseCase entirely).
            gateway.send(chatId, t("savekw.cashRejected"))
            return
        }
        val description = tx.description.trim()
        if (description.isEmpty()) {
            gateway.send(chatId, t("savekw.emptyDescription"))
            return
        }
        gateway.send(
            chatId,
            t("savekw.choose", description),
            keyboard = buildSaveKeywordKeyboard(tx.householdId, record.transactionId),
        )
    }

    private fun sendLogForDecision(txId: String, decision: Decision) {
        val tx = transactionRepository.get(txId).orElse(null) ?: return
        val household = householdRepository.findHousehold(tx.householdId) ?: return
        val category = when (decision) {
            is Decision.Category -> categoryRepository.findById(decision.categoryId) ?: return
            Decision.Ignore -> null
        }
        sendLog(household, tx, category)
    }


    // ----- Help & utilities -----

    private fun sendHelp(msg: Message) {
        reply(
            msg,
            t(
                "help",
                createHouseholdTrigger,
                joinTrigger,
                addCardTrigger,
                inviteTrigger,
                addCategoryTrigger,
                cashTrigger,
                saveKeywordTrigger,
                cancelTrigger,
            ),
        )
    }

    private fun t(code: String, vararg args: Any?): String {
        val stringArgs: Array<Any?> = Array(args.size) { args[it]?.toString() }
        return messageSource.getMessage(code, stringArgs, Locale.ROOT)
    }

    private fun reply(msg: Message, text: String): Int? =
        gateway.send(msg.chat().id(), text, replyToMessageId = msg.messageId())?.toInt()

    /** Send a plain info message to every member of [householdId] *except* [excludeChatId].
     *  Used to let other household members know that a category was added or a card was linked. */
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
            ?: t("displayName.unknown")

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
            InlineKeyboardButton(t("keyboard.ignore")).callbackData("c|$transactionId|-1"),
            InlineKeyboardButton(t("keyboard.other")).callbackData("c|$transactionId|${other.sheetRow}"),
        )
        return InlineKeyboardMarkup(*rows.toTypedArray())
    }

    private fun buildSaveKeywordKeyboard(householdId: UUID, transactionId: String): InlineKeyboardMarkup {
        val regular = categoryRepository.findAll(householdId)
            .filter { !it.isOther }
            .sortedBy { it.sheetRow }
        val rows = regular.chunked(3).map { chunk ->
            chunk.map { cat ->
                InlineKeyboardButton(cat.displayName).callbackData("k|$transactionId|${cat.sheetRow}")
            }.toTypedArray()
        }
        return InlineKeyboardMarkup(*rows.toTypedArray())
    }

    private fun parseCallbackData(householdId: UUID, data: String): Parsed? {
        val parts = data.split('|')
        if (parts.firstOrNull() != "c" || parts.size != 3) return null
        val sheetRow = parts[2].toIntOrNull() ?: return null
        if (sheetRow == -1) return Parsed(parts[1], Decision.Ignore)
        val category = categoryRepository.findBySheetRow(householdId, sheetRow) ?: return null
        return Parsed(parts[1], Decision.Category(category.id))
    }

    private fun parseSaveKeywordCallback(householdId: UUID, data: String): SaveKeywordCallback? {
        val parts = data.split('|')
        if (parts.firstOrNull() != "k" || parts.size != 3) return null
        val sheetRow = parts[2].toIntOrNull() ?: return null
        val category = categoryRepository.findBySheetRow(householdId, sheetRow) ?: return null
        return SaveKeywordCallback(parts[1], category.id)
    }

    private data class Parsed(val txId: String, val decision: Decision)
    private data class SaveKeywordCallback(val txId: String, val categoryId: UUID)

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

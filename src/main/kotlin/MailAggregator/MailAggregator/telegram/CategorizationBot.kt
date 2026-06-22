package MailAggregator.MailAggregator.telegram

import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.common.config.Config.Companion.TIME_ZONE
import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.common.usecases.AddCategoryUseCase
import MailAggregator.MailAggregator.common.usecases.HandleTelegramCommentUseCase
import MailAggregator.MailAggregator.common.usecases.SaveKeywordUseCase
import MailAggregator.MailAggregator.household.BotUser
import MailAggregator.MailAggregator.household.Household
import MailAggregator.MailAggregator.household.repository.HouseholdRepository
import MailAggregator.MailAggregator.household.repository.InviteTokenRepository
import MailAggregator.MailAggregator.household.usecase.AddMonobankAccountUseCase
import MailAggregator.MailAggregator.household.usecase.CreateHouseholdUseCase
import MailAggregator.MailAggregator.household.usecase.JoinHouseholdUseCase
import MailAggregator.MailAggregator.monobank.application.MonoTransaction
import MailAggregator.MailAggregator.monobank.repository.TransactionRepository
import MailAggregator.MailAggregator.telegram.model.CategorizationRequest
import MailAggregator.MailAggregator.telegram.repository.TelegramLogMessageRepository
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.UpdatesListener
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.model.request.ReplyParameters
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.EditMessageReplyMarkup
import com.pengrad.telegrambot.request.SendMessage
import jakarta.annotation.PostConstruct
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class CategorizationBot(
    private val token: String,
    private val categoryRepository: CategoryRepository,
    private val addCategoryUseCase: AddCategoryUseCase,
    private val transactionRepository: TransactionRepository,
    private val telegramLogMessageRepository: TelegramLogMessageRepository,
    private val handleTelegramCommentUseCase: HandleTelegramCommentUseCase,
    private val saveKeywordUseCase: SaveKeywordUseCase,
    private val householdRepository: HouseholdRepository,
    private val createHouseholdUseCase: CreateHouseholdUseCase,
    private val joinHouseholdUseCase: JoinHouseholdUseCase,
    private val addMonobankAccountUseCase: AddMonobankAccountUseCase,
    private val inviteTokenRepository: InviteTokenRepository,
    private val zoneId: ZoneId = TIME_ZONE,
    private val onDecision: (txId: String, decision: Decision) -> Unit,
) {
    private val bot = TelegramBot(token)
    private val addCategoryStates = java.util.concurrent.ConcurrentHashMap<Long, AddCategoryState>()
    private val createHouseholdStates = java.util.concurrent.ConcurrentHashMap<Long, CreateHouseholdState>()
    private val addCardStates = java.util.concurrent.ConcurrentHashMap<Long, AddCardState>()

    @PostConstruct
    fun startLongPolling() {
        bot.setUpdatesListener({ updates ->
            updates.forEach { handleUpdate(it) }
            UpdatesListener.CONFIRMED_UPDATES_ALL
        }, { e ->
            // логируй e.response()?.description() / network errors
        })
    }

    fun sendTx(transaction: CategorizationRequest) {
        val text = buildString {
            appendLine("🧾 ${transaction.description}")
            appendLine("ID: ${transaction.transactionId}")
            appendLine("Time: ${transaction.transactionTime}")
            appendLine("Amount: ${transaction.amount}")
        }
        val keyboard = buildKeyboard(transaction.householdId, transaction.transactionId)
        val users = householdRepository.findUsersInHousehold(transaction.householdId)
        for (user in users) {
            bot.execute(SendMessage(user.chatId, text).replyMarkup(keyboard))
        }
    }

    fun sendLog(household: Household, transaction: MonoTransaction, category: Category?) {
        val zoned = Instant.ofEpochSecond(transaction.raw.time).atZone(zoneId)
        val date = zoned.format(DATE_FORMAT)
        val time = zoned.format(TIME_FORMAT)
        val amount = "%.2f".format(-transaction.raw.amount.toDouble() / 100.0)
        val currency = currencyCode(transaction.raw.currencyCode)
        val tail = category?.let { "Категория: ${it.displayName}" } ?: "Игнорировано"

        val text = buildString {
            appendLine("🧾 ${transaction.raw.description}")
            appendLine("$date $time  −$amount $currency")
            append(tail)
        }
        val users = householdRepository.findUsersInHousehold(household.id)
        for (user in users) {
            val response = bot.execute(SendMessage(user.chatId, text)) ?: continue
            val messageId = response.message()?.messageId()?.toLong() ?: continue
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
                bot.execute(SendMessage(chatId, "OK. Your chatId=$chatId"))
                return
            }

            // ===== Public commands (work for unregistered chats too) =====

            val createState = createHouseholdStates[chatId]
            if (createState != null && replyTo != null && replyTo.messageId() == createState.lastPromptMessageId) {
                handleCreateHouseholdStep(chatId, msg, text, createState)
                return
            }
            if (createState != null && replyTo != null && replyTo.from()?.isBot == true &&
                text.trim().equals(CANCEL_TRIGGER, ignoreCase = true)
            ) {
                createHouseholdStates.remove(chatId)
                reply(msg, "Окей, забил.")
                return
            }
            if (replyTo == null && text.trim().equals(CREATE_HOUSEHOLD_TRIGGER, ignoreCase = true)) {
                resetAllFlows(chatId, msg, restarting = createState != null)
                startCreateHouseholdFlow(chatId, msg)
                return
            }
            if (replyTo == null && text.trim().startsWith(JOIN_TRIGGER, ignoreCase = true)) {
                handleJoinCommand(chatId, msg, text)
                return
            }

            // ===== From here on, the chat must belong to a registered user =====

            val user = householdRepository.findUserByChatId(chatId)
            if (user == null) {
                if (replyTo == null && text.trim().lowercase() in HELP_TRIGGERS) {
                    sendHelp(msg)
                    return
                }
                if (replyTo == null) {
                    reply(
                        msg,
                        "Ты не зарегистрирован. Создай таблицу командой «$CREATE_HOUSEHOLD_TRIGGER» " +
                            "или попроси кого-то прислать тебе «$JOIN_TRIGGER <код>». /help для деталей.",
                    )
                }
                return
            }
            val household = householdRepository.findHousehold(user.householdId) ?: return

            // ===== Mid-flow handlers for registered users =====

            val addState = addCategoryStates[chatId]
            val cardState = addCardStates[chatId]

            if (addState != null && replyTo != null && replyTo.from()?.isBot == true &&
                text.trim().equals(CANCEL_TRIGGER, ignoreCase = true)
            ) {
                cancelAddCategoryFlow(chatId, msg)
                return
            }
            if (cardState != null && replyTo != null && replyTo.from()?.isBot == true &&
                text.trim().equals(CANCEL_TRIGGER, ignoreCase = true)
            ) {
                addCardStates.remove(chatId)
                reply(msg, "Окей, забил.")
                return
            }

            if (addState != null && replyTo != null && replyTo.messageId() == addState.lastPromptMessageId) {
                handleAddCategoryStep(chatId, msg, text, addState, household)
                return
            }
            if (cardState != null && replyTo != null && replyTo.messageId() == cardState.lastPromptMessageId) {
                handleAddCardStep(chatId, msg, text, cardState, user)
                return
            }

            // ===== Plain-message triggers for registered users =====

            if (replyTo == null && text.trim().equals(ADD_CATEGORY_TRIGGER, ignoreCase = true)) {
                resetAllFlows(chatId, msg, restarting = addState != null)
                startAddCategoryFlow(chatId, msg)
                return
            }
            if (replyTo == null && text.trim().equals(ADD_CARD_TRIGGER, ignoreCase = true)) {
                resetAllFlows(chatId, msg, restarting = cardState != null)
                startAddCardFlow(chatId, msg)
                return
            }
            if (replyTo == null && text.trim().equals(INVITE_TRIGGER, ignoreCase = true)) {
                handleInviteCommand(msg, user)
                return
            }

            if (replyTo != null && replyTo.from()?.isBot == true) {
                handleCommentReply(chatId, replyTo.messageId().toLong(), text)
                return
            }

            if (replyTo == null && text.trim().lowercase() in HELP_TRIGGERS) {
                sendHelp(msg)
                return
            }

            if (replyTo == null) {
                reply(msg, "Не понял. Напиши «помощь» чтобы увидеть, что я умею.")
                return
            }
            return
        }

        val cq = update.callbackQuery() ?: return
        val chatId = cq.message()?.chat()?.id() ?: return
        val user = householdRepository.findUserByChatId(chatId)
        if (user == null) {
            bot.execute(AnswerCallbackQuery(cq.id()).text("Not allowed"))
            return
        }

        val data = cq.data() ?: return
        when (data.firstOrNull()) {
            'c' -> handleCategorizationCallback(cq, chatId, user, data)
            'k' -> handleSaveKeywordCallback(cq, chatId, user, data)
            else -> bot.execute(AnswerCallbackQuery(cq.id()).text("Bad callback"))
        }
    }

    /** Drop any active multi-step flow for this chat. Used when a new plain trigger is received
     *  so the user doesn't end up trapped in a half-finished flow they forgot about. */
    private fun resetAllFlows(chatId: Long, msg: Message, restarting: Boolean) {
        val hadFlow = addCategoryStates.remove(chatId) != null ||
            createHouseholdStates.remove(chatId) != null ||
            addCardStates.remove(chatId) != null
        if (hadFlow && restarting) {
            reply(msg, "ℹ️ Старый флоу прерван, ничего не сохранено. Начинаю заново.")
        } else if (hadFlow) {
            reply(msg, "ℹ️ Старый флоу прерван, ничего не сохранено.")
        }
    }

    // ----- Create household flow -----

    private fun startCreateHouseholdFlow(chatId: Long, msg: Message) {
        if (householdRepository.findUserByChatId(chatId) != null) {
            reply(msg, "Ты уже в household. Создавать новую нельзя.")
            return
        }
        val promptId = reply(
            msg,
            "Дай ID Google Sheet'а (тот длинный код из URL таблицы). " +
                "На любом шаге ответь «$CANCEL_TRIGGER» чтобы выйти.",
        ) ?: return
        createHouseholdStates[chatId] = CreateHouseholdState.AwaitingSheetId(promptId)
    }

    private fun handleCreateHouseholdStep(
        chatId: Long,
        msg: Message,
        rawText: String,
        state: CreateHouseholdState,
    ) {
        val text = rawText.trim()
        when (state) {
            is CreateHouseholdState.AwaitingSheetId -> {
                if (text.isEmpty()) {
                    val promptId = reply(msg, "Пустое не подходит. Дай ID Google Sheet'а:") ?: return
                    createHouseholdStates[chatId] = CreateHouseholdState.AwaitingSheetId(promptId)
                    return
                }
                val promptId = reply(
                    msg,
                    "Дай название template-листа (как называется лист, который служит шаблоном для месяцев, например «Февраль 2026»):",
                ) ?: return
                createHouseholdStates[chatId] = CreateHouseholdState.AwaitingTemplateTitle(promptId, text)
            }
            is CreateHouseholdState.AwaitingTemplateTitle -> {
                if (text.isEmpty()) {
                    val promptId = reply(msg, "Пустое не подходит. Дай название template-листа:") ?: return
                    createHouseholdStates[chatId] = CreateHouseholdState.AwaitingTemplateTitle(promptId, state.sheetId)
                    return
                }
                val result = try {
                    createHouseholdUseCase.create(chatId, state.sheetId, text)
                } catch (e: Exception) {
                    println("Failed to create household for chat $chatId: ${e.message}")
                    createHouseholdStates.remove(chatId)
                    reply(msg, "❌ Не получилось создать household: ${e.message}")
                    return
                }
                createHouseholdStates.remove(chatId)
                when (result) {
                    is CreateHouseholdUseCase.Result.Created -> reply(
                        msg,
                        "✓ Создал твою таблицу и засеял 23 категории по умолчанию.\n" +
                            "Не забудь дать боту доступ — расшарь Google Sheet на email service-аккаунта " +
                            "(он в service-account.json). Дальше — «$ADD_CARD_TRIGGER».",
                    )
                    CreateHouseholdUseCase.Result.AlreadyInHousehold -> reply(
                        msg,
                        "Ты уже в household. Создавать новую нельзя.",
                    )
                }
            }
        }
    }

    // ----- Invite / Join -----

    private fun handleInviteCommand(msg: Message, user: BotUser) {
        val token = inviteTokenRepository.create(user.householdId)
        reply(
            msg,
            "✓ Инвайт-код: $token\n" +
                "Перешли этому человеку: «$JOIN_TRIGGER $token». Код одноразовый.",
        )
    }

    private fun handleJoinCommand(chatId: Long, msg: Message, text: String) {
        val token = text.trim().removePrefix(JOIN_TRIGGER).trim()
        if (token.isEmpty()) {
            reply(msg, "Usage: «$JOIN_TRIGGER <код>» — попроси код у того кто уже в боте.")
            return
        }
        val result = joinHouseholdUseCase.join(chatId, token)
        when (result) {
            is JoinHouseholdUseCase.Result.Joined -> reply(
                msg,
                "✓ Добро пожаловать! Ты в household. Привяжи свою карту — «$ADD_CARD_TRIGGER».",
            )
            JoinHouseholdUseCase.Result.AlreadyInHousehold -> reply(
                msg,
                "Ты уже в household. Присоединяться второй раз нельзя.",
            )
            JoinHouseholdUseCase.Result.InvalidToken -> reply(
                msg,
                "❌ Неизвестный или уже использованный код.",
            )
        }
    }

    // ----- Add card flow -----

    private fun startAddCardFlow(chatId: Long, msg: Message) {
        val promptId = reply(
            msg,
            "Дай Monobank API токен (получи на api.monobank.ua). " +
                "На любом шаге ответь «$CANCEL_TRIGGER» чтобы выйти.",
        ) ?: return
        addCardStates[chatId] = AddCardState.AwaitingToken(promptId)
    }

    private fun handleAddCardStep(
        chatId: Long,
        msg: Message,
        rawText: String,
        state: AddCardState,
        user: BotUser,
    ) {
        val text = rawText.trim()
        when (state) {
            is AddCardState.AwaitingToken -> {
                if (text.isEmpty()) {
                    val promptId = reply(msg, "Пустое не подходит. Дай Monobank токен:") ?: return
                    addCardStates[chatId] = AddCardState.AwaitingToken(promptId)
                    return
                }
                val promptId = reply(
                    msg,
                    "Дай Monobank accountId (тот, который тебе нужен — из /api/mono/client-info или из приложения):",
                ) ?: return
                addCardStates[chatId] = AddCardState.AwaitingAccountId(promptId, text)
            }
            is AddCardState.AwaitingAccountId -> {
                if (text.isEmpty()) {
                    val promptId = reply(msg, "Пустое не подходит. Дай accountId:") ?: return
                    addCardStates[chatId] = AddCardState.AwaitingAccountId(promptId, state.token)
                    return
                }
                try {
                    addMonobankAccountUseCase.add(user, state.token, text)
                } catch (e: Exception) {
                    println("Failed to add Monobank account for chat $chatId: ${e.message}")
                    addCardStates.remove(chatId)
                    reply(msg, "❌ Не получилось привязать карту: ${e.message}")
                    return
                }
                addCardStates.remove(chatId)
                reply(msg, "✓ Привязал карту. Транзакции скоро начнут прилетать.")
            }
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
            bot.execute(AnswerCallbackQuery(cq.id()).text("Bad callback"))
            return
        }
        onDecision(parsed.txId, parsed.decision)
        val message = cq.message()
        if (message != null) {
            bot.execute(EditMessageReplyMarkup(chatId, message.messageId()))
        }
        bot.execute(AnswerCallbackQuery(cq.id()).text("Saved"))
        sendLogForDecision(parsed.txId, parsed.decision)
    }

    private fun handleSaveKeywordCallback(
        cq: com.pengrad.telegrambot.model.CallbackQuery,
        chatId: Long,
        user: BotUser,
        data: String,
    ) {
        val parsed = parseSaveKeywordCallback(user.householdId, data) ?: run {
            bot.execute(AnswerCallbackQuery(cq.id()).text("Bad callback"))
            return
        }
        val tx = transactionRepository.get(parsed.txId).orElse(null)
        if (tx == null) {
            bot.execute(AnswerCallbackQuery(cq.id()).text("Tx not found"))
            return
        }
        val result = saveKeywordUseCase(parsed.categoryId, tx.raw.description)
        val message = cq.message()
        if (message != null) {
            bot.execute(EditMessageReplyMarkup(chatId, message.messageId()))
        }
        val (callbackText, replyText) = when (result) {
            is SaveKeywordUseCase.Result.Saved ->
                "Saved" to "✓ Добавил «${result.keyword}» в категорию «${result.category.displayName}»"
            is SaveKeywordUseCase.Result.AlreadyPresent ->
                "Already present" to "ℹ️ «${result.keyword}» уже в категории «${result.category.displayName}»"
            SaveKeywordUseCase.Result.CategoryNotFound ->
                "Category not found" to "❌ Категория не найдена"
            SaveKeywordUseCase.Result.EmptyKeyword ->
                "Empty" to "❌ Пустое описание, нечего сохранять"
        }
        bot.execute(AnswerCallbackQuery(cq.id()).text(callbackText))
        bot.execute(SendMessage(chatId, replyText))
    }

    // ----- Reply handling (comment / Сохранить) -----

    private fun handleCommentReply(chatId: Long, replyToMessageId: Long, text: String) {
        if (text.isBlank()) return

        if (text.trim().equals(SAVE_KEYWORD_TRIGGER, ignoreCase = true)) {
            promptSaveKeywordCategory(chatId, replyToMessageId)
            return
        }

        val saved = try {
            handleTelegramCommentUseCase(chatId, replyToMessageId, text)
        } catch (e: Exception) {
            println("Failed to save Telegram comment: ${e.message}")
            bot.execute(SendMessage(chatId, "❌ Не получилось сохранить коммент"))
            return
        }
        val reply = if (saved) "✓ Comment saved" else "❌ Не нашёл транзакцию для этого сообщения"
        bot.execute(SendMessage(chatId, reply))
    }

    private fun promptSaveKeywordCategory(chatId: Long, replyToMessageId: Long) {
        val record = telegramLogMessageRepository.findByChatAndMessage(chatId, replyToMessageId)
        if (record == null) {
            bot.execute(SendMessage(chatId, "❌ Не нашёл транзакцию для этого сообщения"))
            return
        }
        val tx = transactionRepository.get(record.transactionId).orElse(null)
        if (tx == null) {
            bot.execute(SendMessage(chatId, "❌ Транзакция не найдена в БД"))
            return
        }
        val description = tx.raw.description.trim()
        if (description.isEmpty()) {
            bot.execute(SendMessage(chatId, "❌ У транзакции пустое описание, нечего сохранять"))
            return
        }
        bot.execute(
            SendMessage(chatId, "Выбери категорию для «$description»:")
                .replyMarkup(buildSaveKeywordKeyboard(tx.householdId, record.transactionId)),
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

    // ----- Add category flow (existing) -----

    private fun startAddCategoryFlow(chatId: Long, msg: Message) {
        val promptId = reply(
            msg,
            "Дай название категории (заглавные латинские буквы и `_`). " +
                "На любом шаге ответь «$CANCEL_TRIGGER», чтобы выйти.",
        ) ?: return
        addCategoryStates[chatId] = AddCategoryState.AwaitingName(promptId)
    }

    private fun handleAddCategoryStep(
        chatId: Long,
        msg: Message,
        rawText: String,
        state: AddCategoryState,
        household: Household,
    ) {
        val text = rawText.trim()
        when (state) {
            is AddCategoryState.AwaitingName -> handleNameStep(chatId, msg, text, household)
            is AddCategoryState.AwaitingDisplayName -> handleDisplayNameStep(chatId, msg, text, state)
            is AddCategoryState.AwaitingPriority -> handlePriorityStep(chatId, msg, text, state)
            is AddCategoryState.AwaitingKeywords -> handleKeywordsStep(chatId, msg, text, state, household)
        }
    }

    private fun cancelAddCategoryFlow(chatId: Long, msg: Message) {
        addCategoryStates.remove(chatId)
        reply(msg, "Окей, забил.")
    }

    private fun handleNameStep(chatId: Long, msg: Message, name: String, household: Household) {
        if (!name.matches(NAME_PATTERN)) {
            val promptId = reply(msg, "Имя должно быть из заглавных латинских букв и `_`. Попробуй ещё:") ?: return
            addCategoryStates[chatId] = AddCategoryState.AwaitingName(promptId)
            return
        }
        if (categoryRepository.findByName(household.id, name) != null) {
            val promptId = reply(msg, "Категория «$name» уже существует. Дай другое имя:") ?: return
            addCategoryStates[chatId] = AddCategoryState.AwaitingName(promptId)
            return
        }
        val promptId = reply(msg, "Дай читаемое название:") ?: return
        addCategoryStates[chatId] = AddCategoryState.AwaitingDisplayName(promptId, name)
    }

    private fun handleDisplayNameStep(
        chatId: Long,
        msg: Message,
        displayName: String,
        prev: AddCategoryState.AwaitingDisplayName,
    ) {
        if (displayName.isEmpty()) {
            val promptId = reply(msg, "Пустое не подходит. Дай читаемое название:") ?: return
            addCategoryStates[chatId] = prev.copy(lastPromptMessageId = promptId)
            return
        }
        val promptId = reply(msg, "Дай приоритет ($PRIORITY_MIN..$PRIORITY_MAX):") ?: return
        addCategoryStates[chatId] = AddCategoryState.AwaitingPriority(promptId, prev.name, displayName)
    }

    private fun handlePriorityStep(
        chatId: Long,
        msg: Message,
        priorityRaw: String,
        prev: AddCategoryState.AwaitingPriority,
    ) {
        val priority = priorityRaw.toIntOrNull()
        if (priority == null || priority !in PRIORITY_MIN..PRIORITY_MAX) {
            val promptId = reply(msg, "Нужно число от $PRIORITY_MIN до $PRIORITY_MAX. Попробуй ещё:") ?: return
            addCategoryStates[chatId] = prev.copy(lastPromptMessageId = promptId)
            return
        }
        val promptId = reply(
            msg,
            "Дай ключевые слова через запятую, или «$EMPTY_KEYWORDS_TRIGGER» чтобы оставить пустым " +
                "(тогда добавишь через «Сохранить» в будущем):",
        ) ?: return
        addCategoryStates[chatId] = AddCategoryState.AwaitingKeywords(promptId, prev.name, prev.displayName, priority)
    }

    private fun handleKeywordsStep(
        chatId: Long,
        msg: Message,
        keywordsRaw: String,
        prev: AddCategoryState.AwaitingKeywords,
        household: Household,
    ) {
        val keywords = if (keywordsRaw == EMPTY_KEYWORDS_TRIGGER) {
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
            addCategoryStates.remove(chatId)
            reply(msg, "❌ Не получилось создать категорию: ${e.message}")
            return
        }
        addCategoryStates.remove(chatId)
        reply(
            msg,
            "✓ Создал «${category.name}» (${category.displayName}) на sheet_row=${category.sheetRow}.",
        )
    }

    // ----- Help & utilities -----

    private fun sendHelp(msg: Message) {
        reply(msg, HELP_TEXT)
    }

    private fun reply(msg: Message, text: String): Int? {
        val response = bot.execute(
            SendMessage(msg.chat().id(), text)
                .replyParameters(ReplyParameters(msg.messageId())),
        )
        return response?.message()?.messageId()
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
            InlineKeyboardButton("Игнорировать").callbackData("c|$transactionId|-1"),
            InlineKeyboardButton("Другое").callbackData("c|$transactionId|${other.sheetRow}"),
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

    private sealed class AddCategoryState {
        abstract val lastPromptMessageId: Int

        data class AwaitingName(override val lastPromptMessageId: Int) : AddCategoryState()
        data class AwaitingDisplayName(
            override val lastPromptMessageId: Int,
            val name: String,
        ) : AddCategoryState()
        data class AwaitingPriority(
            override val lastPromptMessageId: Int,
            val name: String,
            val displayName: String,
        ) : AddCategoryState()
        data class AwaitingKeywords(
            override val lastPromptMessageId: Int,
            val name: String,
            val displayName: String,
            val priority: Int,
        ) : AddCategoryState()
    }

    private sealed class CreateHouseholdState {
        abstract val lastPromptMessageId: Int

        data class AwaitingSheetId(override val lastPromptMessageId: Int) : CreateHouseholdState()
        data class AwaitingTemplateTitle(
            override val lastPromptMessageId: Int,
            val sheetId: String,
        ) : CreateHouseholdState()
    }

    private sealed class AddCardState {
        abstract val lastPromptMessageId: Int

        data class AwaitingToken(override val lastPromptMessageId: Int) : AddCardState()
        data class AwaitingAccountId(
            override val lastPromptMessageId: Int,
            val token: String,
        ) : AddCardState()
    }

    private data class Parsed(val txId: String, val decision: Decision)
    private data class SaveKeywordCallback(val txId: String, val categoryId: UUID)

    sealed class Decision {
        data class Category(val categoryId: UUID) : Decision()
        data object Ignore : Decision()
    }

    companion object {
        private const val SAVE_KEYWORD_TRIGGER = "Сохранить"
        private const val ADD_CATEGORY_TRIGGER = "Добавить категорию"
        private const val CREATE_HOUSEHOLD_TRIGGER = "Создать таблицу"
        private const val ADD_CARD_TRIGGER = "Привязать карту"
        private const val INVITE_TRIGGER = "Пригласить"
        private const val JOIN_TRIGGER = "Присоединиться"
        private const val CANCEL_TRIGGER = "Забей"
        private const val EMPTY_KEYWORDS_TRIGGER = "-"
        private const val PRIORITY_MIN = 1
        private const val PRIORITY_MAX = 100
        private val NAME_PATTERN = Regex("[A-Z_]+")
        private val HELP_TRIGGERS = setOf("помощь", "/help", "help", "/?", "?")
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val HELP_TEXT = """
            Что я умею:

            /start — узнать свой chatId.

            ── Если ты ещё не зарегистрирован ──
            «Создать таблицу» — пошаговый диалог: создаю household + засеваю дефолтные категории.
            «Присоединиться <код>» — войти в чужую household по инвайт-коду.

            ── Если ты уже в household ──
            «Привязать карту» — пошаговый диалог привязки Monobank-токена + accountId.
            «Пригласить» — сгенерировать одноразовый код, чтобы пригласить кого-то.
            «Добавить категорию» — пошаговый диалог создания новой категории.

            ── Реплаи на лог транзакции ──
            • «Сохранить» — клавиатура выбора категории; description пойдёт в её keywords.
            • Любой другой текст — комментарий в Google Sheets (ряд 2 столбца того дня; через «;»).

            На любом шаге пошагового диалога реплай «Забей» — выйти из флоу.
            «помощь» или /help — показать это сообщение.
        """.trimIndent()

        private fun currencyCode(numericCode: Int): String = when (numericCode) {
            980 -> "UAH"
            840 -> "USD"
            978 -> "EUR"
            826 -> "GBP"
            else -> "ccy:$numericCode"
        }
    }
}

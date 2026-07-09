@file:Suppress("SENSELESS_COMPARISON") // MockK `match { it != null }` on nullable params trips the compiler; the check is meaningful.

package MailAggregator.MailAggregator.telegram

import MailAggregator.MailAggregator.bank.Transaction
import MailAggregator.MailAggregator.bank.TransactionStatus
import MailAggregator.MailAggregator.bank.repository.TransactionRepository
import MailAggregator.MailAggregator.bank.repository.TransactionStatusRepository
import MailAggregator.MailAggregator.common.Category
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
import MailAggregator.MailAggregator.bank.BankAccount
import MailAggregator.MailAggregator.bank.BankType
import MailAggregator.MailAggregator.monobank.api.MonoApiAccount
import MailAggregator.MailAggregator.monobank.api.MonoApiClientInfo
import MailAggregator.MailAggregator.monobank.api.MonobankApi
import MailAggregator.MailAggregator.spreadsheet.Authentication
import MailAggregator.MailAggregator.telegram.model.CategorizationRequest
import MailAggregator.MailAggregator.telegram.repository.TelegramLogMessageRepository
import MailAggregator.MailAggregator.telegram.repository.jpa.TelegramLogMessageJpaEntity
import com.pengrad.telegrambot.model.CallbackQuery
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Update
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.context.support.ResourceBundleMessageSource
import java.time.ZoneId
import java.util.Optional
import java.util.UUID

@ExtendWith(MockKExtension::class)
class CategorizationBotTest {

    private val gateway: TelegramGateway = mockk(relaxed = true)
    private val categoryRepository: CategoryRepository = mockk(relaxed = true)
    private val addCategoryUseCase: AddCategoryUseCase = mockk(relaxed = true)
    private val transactionRepository: TransactionRepository = mockk(relaxed = true)
    private val telegramLogMessageRepository: TelegramLogMessageRepository = mockk(relaxed = true)
    private val handleTelegramCommentUseCase: HandleTelegramCommentUseCase = mockk(relaxed = true)
    private val saveKeywordUseCase: SaveKeywordUseCase = mockk(relaxed = true)
    private val transactionStatusRepository: TransactionStatusRepository = mockk(relaxed = true)
    private val householdRepository: HouseholdRepository = mockk(relaxed = true)
    private val createHouseholdUseCase: CreateHouseholdUseCase = mockk(relaxed = true)
    private val joinHouseholdUseCase: JoinHouseholdUseCase = mockk(relaxed = true)
    private val addBankAccountUseCase: AddBankAccountUseCase = mockk(relaxed = true)
    private val addCashTransactionUseCase: AddCashTransactionUseCase = mockk(relaxed = true)
    private val inviteTokenRepository: InviteTokenRepository = mockk(relaxed = true)
    private val authentication: Authentication = mockk(relaxed = true)
    private val monobankApi: MonobankApi = mockk(relaxed = true)
    private val onDecision: (String, CategorizationBot.Decision) -> Unit = mockk(relaxed = true)

    private val messageSource = ResourceBundleMessageSource().apply {
        setBasename("messages")
        setDefaultEncoding("UTF-8")
    }

    private val zoneId: ZoneId = ZoneId.of("UTC")

    private lateinit var bot: CategorizationBot
    private val onUpdateSlot = slot<(Update) -> Unit>()

    @BeforeEach
    fun setUp() {
        // buildKeyboard() requires at least one isOther=true category; every household has one in prod.
        every { categoryRepository.findAll(any()) } returns listOf(
            category(UUID.randomUUID(), "Other").copy(isOther = true, sheetRow = 999),
        )
        every { gateway.start(capture(onUpdateSlot)) } just Runs
        bot = CategorizationBot(
            gateway = gateway,
            categoryRepository = categoryRepository,
            addCategoryUseCase = addCategoryUseCase,
            transactionRepository = transactionRepository,
            telegramLogMessageRepository = telegramLogMessageRepository,
            handleTelegramCommentUseCase = handleTelegramCommentUseCase,
            saveKeywordUseCase = saveKeywordUseCase,
            transactionStatusRepository = transactionStatusRepository,
            householdRepository = householdRepository,
            createHouseholdUseCase = createHouseholdUseCase,
            joinHouseholdUseCase = joinHouseholdUseCase,
            addBankAccountUseCase = addBankAccountUseCase,
            addCashTransactionUseCase = addCashTransactionUseCase,
            inviteTokenRepository = inviteTokenRepository,
            authentication = authentication,
            monobankApi = monobankApi,
            ingestEmail = "aggregator@example.com",
            messageSource = messageSource,
            zoneId = zoneId,
            onDecision = onDecision,
        )
        bot.startLongPolling() // registers the onUpdate lambda with the gateway (captured into onUpdateSlot)
    }

    /** Deliver an Update to the bot via the captured long-polling callback. */
    private fun feed(update: Update) {
        onUpdateSlot.captured(update)
    }

    @Nested
    inner class SendTx {

        @Test
        fun `broadcasts prompt with keyboard to every household user and persists each returned message id`() {
            // Given: two users in the household, gateway accepts both
            val householdId = UUID.randomUUID()
            val userA = BotUser(id = UUID.randomUUID(), chatId = 111L, name = "A", householdId = householdId)
            val userB = BotUser(id = UUID.randomUUID(), chatId = 222L, name = "B", householdId = householdId)
            every { householdRepository.findUsersInHousehold(householdId) } returns listOf(userA, userB)
            every { gateway.send(111L, any(), any(), any()) } returns 1001L
            every { gateway.send(222L, any(), any(), any()) } returns 1002L

            // When
            bot.sendTx(request(txId = "tx-1", householdId = householdId))

            // Then: each user got a message with a keyboard, no reply-to, and its id was persisted
            verify(exactly = 1) {
                gateway.send(
                    chatId = 111L,
                    text = any(),
                    keyboard = match { it != null },
                    replyToMessageId = null,
                )
            }
            verify(exactly = 1) {
                gateway.send(
                    chatId = 222L,
                    text = any(),
                    keyboard = match { it != null },
                    replyToMessageId = null,
                )
            }
            verify(exactly = 1) { telegramLogMessageRepository.save(householdId, 111L, 1001L, "tx-1") }
            verify(exactly = 1) { telegramLogMessageRepository.save(householdId, 222L, 1002L, "tx-1") }
        }

        @Test
        fun `skips persistence for users whose gateway send returned null (transient send failure)`() {
            // Given: two users, gateway drops the message for the second one
            val householdId = UUID.randomUUID()
            val userA = BotUser(id = UUID.randomUUID(), chatId = 111L, name = "A", householdId = householdId)
            val userB = BotUser(id = UUID.randomUUID(), chatId = 222L, name = "B", householdId = householdId)
            every { householdRepository.findUsersInHousehold(householdId) } returns listOf(userA, userB)
            every { gateway.send(111L, any(), any(), any()) } returns 1001L
            every { gateway.send(222L, any(), any(), any()) } returns null

            // When
            bot.sendTx(request(txId = "tx-2", householdId = householdId))

            // Then: only the successful send persisted; the failed one was silently skipped
            verify(exactly = 1) { telegramLogMessageRepository.save(householdId, 111L, 1001L, "tx-2") }
            verify(exactly = 0) { telegramLogMessageRepository.save(householdId, 222L, any(), any()) }
        }

        @Test
        fun `no-op when the household has no users`() {
            // Given: empty household
            val householdId = UUID.randomUUID()
            every { householdRepository.findUsersInHousehold(householdId) } returns emptyList()

            // When
            bot.sendTx(request(txId = "tx-3", householdId = householdId))

            // Then: no outbound calls at all
            verify(exactly = 0) { gateway.send(any(), any(), any(), any()) }
            verify(exactly = 0) { telegramLogMessageRepository.save(any(), any(), any(), any()) }
        }
    }

    @Nested
    inner class SendLog {

        @Test
        fun `sends categorised log threaded under the prior prompt for each user`() {
            // Given: two users, both have a prior prompt persisted for the tx (typical manual-categorise flow)
            val householdId = UUID.randomUUID()
            val household = Household(id = householdId, name = "H", sheetId = "s", templateSheetTitle = "t")
            val transaction = tx(id = "tx-4", householdId = householdId)
            val category = category(householdId, "Coffee")
            val userA = BotUser(id = UUID.randomUUID(), chatId = 111L, name = "A", householdId = householdId)
            val userB = BotUser(id = UUID.randomUUID(), chatId = 222L, name = "B", householdId = householdId)
            val priorA = logRow(householdId, chatId = 111L, messageId = 500L, transactionId = "tx-4")
            val priorB = logRow(householdId, chatId = 222L, messageId = 600L, transactionId = "tx-4")
            every { telegramLogMessageRepository.findAllByTransactionId("tx-4") } returns listOf(priorA, priorB)
            every { householdRepository.findUsersInHousehold(householdId) } returns listOf(userA, userB)
            every { gateway.send(111L, any(), any(), 500) } returns 1101L
            every { gateway.send(222L, any(), any(), 600) } returns 1102L

            // When
            bot.sendLog(household, transaction, category)

            // Then: each user got a log threaded under their own prior prompt; each new id persisted
            verify(exactly = 1) { gateway.send(chatId = 111L, text = any(), keyboard = null, replyToMessageId = 500) }
            verify(exactly = 1) { gateway.send(chatId = 222L, text = any(), keyboard = null, replyToMessageId = 600) }
            verify(exactly = 1) { telegramLogMessageRepository.save(householdId, 111L, 1101L, "tx-4") }
            verify(exactly = 1) { telegramLogMessageRepository.save(householdId, 222L, 1102L, "tx-4") }
        }

        @Test
        fun `sends log as a fresh message when there is no prior prompt (auto-categorised flow)`() {
            // Given: no prior prompt in the DB (auto-categorised tx)
            val householdId = UUID.randomUUID()
            val household = Household(id = householdId, name = "H", sheetId = "s", templateSheetTitle = "t")
            val transaction = tx(id = "tx-5", householdId = householdId)
            val userA = BotUser(id = UUID.randomUUID(), chatId = 111L, name = "A", householdId = householdId)
            every { telegramLogMessageRepository.findAllByTransactionId("tx-5") } returns emptyList()
            every { householdRepository.findUsersInHousehold(householdId) } returns listOf(userA)
            every { gateway.send(111L, any(), any(), null) } returns 1105L

            // When
            bot.sendLog(household, transaction, category = null)

            // Then: no replyToMessageId used, still persisted
            verify(exactly = 1) { gateway.send(chatId = 111L, text = any(), keyboard = null, replyToMessageId = null) }
            verify(exactly = 1) { telegramLogMessageRepository.save(householdId, 111L, 1105L, "tx-5") }
        }

        @Test
        fun `skips persistence for users whose log send failed`() {
            // Given: prior prompts for both users but gateway drops the second send
            val householdId = UUID.randomUUID()
            val household = Household(id = householdId, name = "H", sheetId = "s", templateSheetTitle = "t")
            val transaction = tx(id = "tx-6", householdId = householdId)
            val category = category(householdId, "Groceries")
            val userA = BotUser(id = UUID.randomUUID(), chatId = 111L, name = "A", householdId = householdId)
            val userB = BotUser(id = UUID.randomUUID(), chatId = 222L, name = "B", householdId = householdId)
            every { telegramLogMessageRepository.findAllByTransactionId("tx-6") } returns emptyList()
            every { householdRepository.findUsersInHousehold(householdId) } returns listOf(userA, userB)
            every { gateway.send(111L, any(), any(), null) } returns 1106L
            every { gateway.send(222L, any(), any(), null) } returns null

            // When
            bot.sendLog(household, transaction, category)

            // Then: only the successful send is persisted
            verify(exactly = 1) { telegramLogMessageRepository.save(householdId, 111L, 1106L, "tx-6") }
            verify(exactly = 0) { telegramLogMessageRepository.save(householdId, 222L, any(), any()) }
        }
    }

    @Nested
    inner class OneShotCommands {

        @Test
        fun `slash-start sends greeting`() {
            // Given
            val chatId = 1001L
            val update = textUpdate(chatId, "/start", replyTo = null)

            // When
            feed(update)

            // Then
            verify(exactly = 1) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = null) }
        }

        @Test
        fun `help trigger from an unregistered chat sends help text`() {
            // Given: no user for this chat
            val chatId = 2001L
            every { householdRepository.findUserByChatId(chatId) } returns null
            val update = textUpdate(chatId, "/help", messageId = 42)

            // When
            feed(update)

            // Then: reply threaded under the incoming message
            verify(exactly = 1) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = 42) }
        }

        @Test
        fun `help trigger from a registered chat also sends help text`() {
            // Given: registered user
            val chatId = 2002L
            val user = botUser(chatId)
            every { householdRepository.findUserByChatId(chatId) } returns user
            every { householdRepository.findHousehold(user.householdId) } returns household(user.householdId)
            val update = textUpdate(chatId, "help", messageId = 43)

            // When
            feed(update)

            // Then
            verify(exactly = 1) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = 43) }
        }

        @Test
        fun `unregistered chat sending unknown text is told to register`() {
            // Given: no user
            val chatId = 3001L
            every { householdRepository.findUserByChatId(chatId) } returns null
            val update = textUpdate(chatId, "some random message", messageId = 55)

            // When
            feed(update)

            // Then: single reply, no join/create side-effects
            verify(exactly = 1) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = 55) }
            verify(exactly = 0) { joinHouseholdUseCase.join(any(), any()) }
        }

        @Test
        fun `registered chat sending unknown text gets the unknown-command reply`() {
            // Given: registered user, no mid-flow state
            val chatId = 3002L
            val user = botUser(chatId)
            every { householdRepository.findUserByChatId(chatId) } returns user
            every { householdRepository.findHousehold(user.householdId) } returns household(user.householdId)
            val update = textUpdate(chatId, "blah blah", messageId = 60)

            // When
            feed(update)

            // Then: single reply threaded under the message; no follow-up outbound calls
            verify(exactly = 1) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = 60) }
        }

        @Test
        fun `invite command asks the token repo for a fresh token and replies with it`() {
            // Given: registered user, generate returns a specific token
            val chatId = 4001L
            val user = botUser(chatId)
            every { householdRepository.findUserByChatId(chatId) } returns user
            every { householdRepository.findHousehold(user.householdId) } returns household(user.householdId)
            every { inviteTokenRepository.create(user.householdId) } returns "tok-abc-123"
            val update = textUpdate(chatId, "Пригласить", messageId = 70)

            // When
            feed(update)

            // Then: token created for this household, reply sent under the trigger message
            verify(exactly = 1) { inviteTokenRepository.create(user.householdId) }
            verify(exactly = 1) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = 70) }
        }

        @Test
        fun `join with an empty token replies with usage instead of calling the use case`() {
            // Given: chat with no user; the "Присоединиться" trigger alone (no token)
            val chatId = 5001L
            every { householdRepository.findUserByChatId(chatId) } returns null
            val update = textUpdate(chatId, "Присоединиться", messageId = 80)

            // When
            feed(update)

            // Then: usage reply, join use case not invoked
            verify(exactly = 1) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = 80) }
            verify(exactly = 0) { joinHouseholdUseCase.join(any(), any()) }
        }

        @Test
        fun `join with a valid token delegates to the use case (Joined result)`() {
            // Given: fresh chat, join returns Joined
            val chatId = 5002L
            val householdId = UUID.randomUUID()
            val joinedUser = botUser(chatId, householdId)
            every { householdRepository.findUserByChatId(chatId) } returns null
            every { joinHouseholdUseCase.join(chatId, "tok-xyz") } returns
                JoinHouseholdUseCase.Result.Joined(household(householdId), joinedUser)
            val update = textUpdate(chatId, "Присоединиться tok-xyz", messageId = 81)

            // When
            feed(update)

            // Then
            verify(exactly = 1) { joinHouseholdUseCase.join(chatId, "tok-xyz") }
            verify(exactly = 1) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = 81) }
        }

        @Test
        fun `join with an invalid token replies with the invalidToken message`() {
            // Given
            val chatId = 5003L
            every { householdRepository.findUserByChatId(chatId) } returns null
            every { joinHouseholdUseCase.join(chatId, "bad") } returns JoinHouseholdUseCase.Result.InvalidToken
            val update = textUpdate(chatId, "Присоединиться bad", messageId = 82)

            // When
            feed(update)

            // Then
            verify(exactly = 1) { joinHouseholdUseCase.join(chatId, "bad") }
            verify(exactly = 1) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = 82) }
        }

        @Test
        fun `join when already in a household replies with alreadyJoined`() {
            // Given
            val chatId = 5004L
            every { householdRepository.findUserByChatId(chatId) } returns null
            every { joinHouseholdUseCase.join(chatId, "tok-2") } returns JoinHouseholdUseCase.Result.AlreadyInHousehold
            val update = textUpdate(chatId, "Присоединиться tok-2", messageId = 83)

            // When
            feed(update)

            // Then
            verify(exactly = 1) { joinHouseholdUseCase.join(chatId, "tok-2") }
            verify(exactly = 1) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = 83) }
        }
    }

    private enum class AddCategoryStep { AwaitingName, AwaitingDisplayName, AwaitingPriority, AwaitingKeywords }

    @Nested
    inner class AddCategoryWizard {

        /**
         * Drive the wizard up to the given step by feeding valid inputs, so each test only asserts the
         * step it cares about. Returns the promptId of the current bot prompt (i.e. the id the *next*
         * user reply must set as its replyTo).
         *
         * Each bot outgoing message returns `promptSeq++` as its id; that same id is threaded back in
         * subsequent updates via [botReplyPrompt] to match the router's `replyTo.messageId() == state.lastPromptMessageId`
         * requirement.
         */
        private fun driveThrough(step: AddCategoryStep, chatId: Long, household: Household): Int {
            var promptId = 100
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } answers { (promptId++).toLong() }
            // trigger word starts the wizard
            feed(textUpdate(chatId, "Добавить категорию", messageId = 1))
            if (step == AddCategoryStep.AwaitingName) return promptId - 1
            // AwaitingName -> AwaitingDisplayName
            every { categoryRepository.findByName(household.id, "COFFEE") } returns null
            feed(textUpdate(chatId, "COFFEE", messageId = 2, replyTo = botReplyPrompt(promptId - 1)))
            if (step == AddCategoryStep.AwaitingDisplayName) return promptId - 1
            // AwaitingDisplayName -> AwaitingPriority
            feed(textUpdate(chatId, "Coffee shops", messageId = 3, replyTo = botReplyPrompt(promptId - 1)))
            if (step == AddCategoryStep.AwaitingPriority) return promptId - 1
            // AwaitingPriority -> AwaitingKeywords
            feed(textUpdate(chatId, "50", messageId = 4, replyTo = botReplyPrompt(promptId - 1)))
            return promptId - 1
        }

        @Test
        fun `trigger starts the wizard and asks for the name`() {
            val chatId = 6001L
            registerUser(chatId)
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returns 100L

            feed(textUpdate(chatId, "Добавить категорию", messageId = 1))

            // Reply is threaded under the trigger message; no wizard completion yet.
            verify(exactly = 1) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = 1) }
            verify(exactly = 0) { addCategoryUseCase.add(any(), any(), any(), any(), any()) }
        }

        @Test
        fun `name that fails the pattern re-prompts and keeps the wizard in AwaitingName`() {
            val chatId = 6002L
            val (_, household) = registerUser(chatId)
            val promptId = driveThrough(AddCategoryStep.AwaitingName, chatId, household)

            // "coffee" lower-case fails validation.namePattern = [A-Z_]+
            feed(textUpdate(chatId, "coffee", messageId = 10, replyTo = botReplyPrompt(promptId)))

            // No lookup, no downstream call — just a re-prompt.
            verify(exactly = 0) { categoryRepository.findByName(any(), any()) }
            verify(exactly = 0) { addCategoryUseCase.add(any(), any(), any(), any(), any()) }
            // Two outbound sends so far: the initial ask + the bad-name re-prompt.
            verify(exactly = 2) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = any()) }
        }

        @Test
        fun `name that already exists in the household re-prompts and keeps the wizard in AwaitingName`() {
            val chatId = 6003L
            val (_, household) = registerUser(chatId)
            every { categoryRepository.findByName(household.id, "COFFEE") } returns
                category(household.id, "Coffee shops").copy(name = "COFFEE")
            val promptId = driveThrough(AddCategoryStep.AwaitingName, chatId, household)

            feed(textUpdate(chatId, "COFFEE", messageId = 11, replyTo = botReplyPrompt(promptId)))

            // Existing-name lookup happened, but no use case call and no state progression.
            verify(exactly = 1) { categoryRepository.findByName(household.id, "COFFEE") }
            verify(exactly = 0) { addCategoryUseCase.add(any(), any(), any(), any(), any()) }
            verify(exactly = 2) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = any()) }
        }

        @Test
        fun `empty displayName re-prompts and keeps the wizard in AwaitingDisplayName`() {
            val chatId = 6004L
            val (_, household) = registerUser(chatId)
            val promptId = driveThrough(AddCategoryStep.AwaitingDisplayName, chatId, household)

            feed(textUpdate(chatId, "", messageId = 20, replyTo = botReplyPrompt(promptId)))

            verify(exactly = 0) { addCategoryUseCase.add(any(), any(), any(), any(), any()) }
            // start + name-ok reply + displayName re-prompt = 3 sends
            verify(exactly = 3) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = any()) }
        }

        @Test
        fun `non-numeric priority re-prompts`() {
            val chatId = 6005L
            val (_, household) = registerUser(chatId)
            val promptId = driveThrough(AddCategoryStep.AwaitingPriority, chatId, household)

            feed(textUpdate(chatId, "abc", messageId = 30, replyTo = botReplyPrompt(promptId)))

            verify(exactly = 0) { addCategoryUseCase.add(any(), any(), any(), any(), any()) }
            // start + name-ok + displayName-ok + priority-bad = 4 sends
            verify(exactly = 4) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = any()) }
        }

        @Test
        fun `priority below range re-prompts`() {
            val chatId = 6006L
            val (_, household) = registerUser(chatId)
            val promptId = driveThrough(AddCategoryStep.AwaitingPriority, chatId, household)

            feed(textUpdate(chatId, "0", messageId = 31, replyTo = botReplyPrompt(promptId)))

            verify(exactly = 0) { addCategoryUseCase.add(any(), any(), any(), any(), any()) }
            verify(exactly = 4) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = any()) }
        }

        @Test
        fun `priority above range re-prompts`() {
            val chatId = 6007L
            val (_, household) = registerUser(chatId)
            val promptId = driveThrough(AddCategoryStep.AwaitingPriority, chatId, household)

            feed(textUpdate(chatId, "101", messageId = 32, replyTo = botReplyPrompt(promptId)))

            verify(exactly = 0) { addCategoryUseCase.add(any(), any(), any(), any(), any()) }
            verify(exactly = 4) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = any()) }
        }

        @Test
        fun `empty-keywords trigger completes the wizard with an empty keyword list`() {
            val chatId = 6008L
            val (_, household) = registerUser(chatId)
            val promptId = driveThrough(AddCategoryStep.AwaitingKeywords, chatId, household)
            every {
                addCategoryUseCase.add(household, "COFFEE", "Coffee shops", 50, emptyList())
            } returns category(household.id, "Coffee shops").copy(name = "COFFEE", priority = 50)

            feed(textUpdate(chatId, "-", messageId = 40, replyTo = botReplyPrompt(promptId)))

            verify(exactly = 1) {
                addCategoryUseCase.add(
                    household = household,
                    name = "COFFEE",
                    displayName = "Coffee shops",
                    priority = 50,
                    keywords = emptyList(),
                )
            }
        }

        @Test
        fun `csv keyword list completes the wizard with trimmed non-empty items`() {
            val chatId = 6009L
            val (_, household) = registerUser(chatId)
            val promptId = driveThrough(AddCategoryStep.AwaitingKeywords, chatId, household)
            every {
                addCategoryUseCase.add(household, "COFFEE", "Coffee shops", 50, listOf("coffee", "latte", "espresso"))
            } returns category(household.id, "Coffee shops").copy(name = "COFFEE", priority = 50)

            feed(textUpdate(chatId, "coffee, latte, espresso", messageId = 41, replyTo = botReplyPrompt(promptId)))

            verify(exactly = 1) {
                addCategoryUseCase.add(
                    household = household,
                    name = "COFFEE",
                    displayName = "Coffee shops",
                    priority = 50,
                    keywords = listOf("coffee", "latte", "espresso"),
                )
            }
        }

        @Test
        fun `use case failure clears the wizard state and reports the error`() {
            val chatId = 6010L
            val (_, household) = registerUser(chatId)
            val promptId = driveThrough(AddCategoryStep.AwaitingKeywords, chatId, household)
            every {
                addCategoryUseCase.add(household, "COFFEE", "Coffee shops", 50, emptyList())
            } throws IllegalStateException("db is on fire")

            feed(textUpdate(chatId, "-", messageId = 42, replyTo = botReplyPrompt(promptId)))

            // Failure message sent; the wizard is dead — feeding another AwaitingKeywords reply
            // to the same promptId must not re-invoke the use case.
            feed(textUpdate(chatId, "coffee", messageId = 43, replyTo = botReplyPrompt(promptId)))
            verify(exactly = 1) { addCategoryUseCase.add(any(), any(), any(), any(), any()) }
        }

        @Test
        fun `cancel mid-wizard clears state and confirms cancellation`() {
            val chatId = 6011L
            val (_, household) = registerUser(chatId)
            val promptId = driveThrough(AddCategoryStep.AwaitingDisplayName, chatId, household)

            // Cancel by replying "Забей" to the current bot prompt.
            feed(textUpdate(chatId, "Забей", messageId = 50, replyTo = botReplyPrompt(promptId)))

            // Any further reply to the same prompt must NOT be treated as a wizard step anymore.
            feed(textUpdate(chatId, "Coffee shops", messageId = 51, replyTo = botReplyPrompt(promptId)))
            verify(exactly = 0) { addCategoryUseCase.add(any(), any(), any(), any(), any()) }
        }
    }

    @Nested
    inner class CreateHouseholdWizard {

        @Test
        fun `trigger while unregistered asks for sheet id and saves AwaitingSheetId state`() {
            val chatId = 7001L
            every { householdRepository.findUserByChatId(chatId) } returns null
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returns 200L

            feed(textUpdate(chatId, "Создать таблицу", messageId = 1))

            verify(exactly = 1) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = 1) }
            verify(exactly = 0) { createHouseholdUseCase.create(any(), any()) }
        }

        @Test
        fun `trigger while already registered rejects with alreadyInHousehold and skips the wizard`() {
            val chatId = 7002L
            registerUser(chatId)

            feed(textUpdate(chatId, "Создать таблицу", messageId = 1))

            // Router still delivers a reply, but no wizard state is created — a subsequent
            // reply-to-this-message won't be treated as a step.
            verify(exactly = 1) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = 1) }
            verify(exactly = 0) { createHouseholdUseCase.create(any(), any()) }

            // Confirm no wizard state was saved: replying to the ack with any text is treated as unknown-text, not as a sheetId.
            feed(textUpdate(chatId, "abc123", messageId = 2, replyTo = botReplyPrompt(1)))
            verify(exactly = 0) { createHouseholdUseCase.create(any(), any()) }
        }

        @Test
        fun `empty sheet id re-prompts, staying in AwaitingSheetId`() {
            val chatId = 7003L
            every { householdRepository.findUserByChatId(chatId) } returns null
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returnsMany listOf(200L, 201L)

            feed(textUpdate(chatId, "Создать таблицу", messageId = 1))
            feed(textUpdate(chatId, "   ", messageId = 2, replyTo = botReplyPrompt(200)))

            verify(exactly = 0) { createHouseholdUseCase.create(any(), any()) }
            // start + empty-sheet re-prompt = 2 sends
            verify(exactly = 2) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = any()) }
        }

        @Test
        fun `raw sheet id is passed to the use case verbatim on Created result`() {
            val chatId = 7004L
            val householdId = UUID.randomUUID()
            every { householdRepository.findUserByChatId(chatId) } returns null
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returnsMany listOf(200L, 201L)
            every { createHouseholdUseCase.create(chatId, "raw-sheet-id") } returns
                CreateHouseholdUseCase.Result.Created(household(householdId), botUser(chatId, householdId))

            feed(textUpdate(chatId, "Создать таблицу", messageId = 1))
            feed(textUpdate(chatId, "raw-sheet-id", messageId = 2, replyTo = botReplyPrompt(200)))

            verify(exactly = 1) { createHouseholdUseCase.create(chatId, "raw-sheet-id") }
        }

        @Test
        fun `full google sheets url is parsed and only the id is passed to the use case`() {
            val chatId = 7005L
            val householdId = UUID.randomUUID()
            every { householdRepository.findUserByChatId(chatId) } returns null
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returnsMany listOf(200L, 201L)
            every { createHouseholdUseCase.create(chatId, "1AbC_defGhi-jkl") } returns
                CreateHouseholdUseCase.Result.Created(household(householdId), botUser(chatId, householdId))

            feed(textUpdate(chatId, "Создать таблицу", messageId = 1))
            feed(
                textUpdate(
                    chatId,
                    "https://docs.google.com/spreadsheets/d/1AbC_defGhi-jkl/edit#gid=0",
                    messageId = 2,
                    replyTo = botReplyPrompt(200),
                ),
            )

            verify(exactly = 1) { createHouseholdUseCase.create(chatId, "1AbC_defGhi-jkl") }
        }

        @Test
        fun `AlreadyInHousehold result clears state and sends the alreadyInHousehold reply`() {
            val chatId = 7006L
            every { householdRepository.findUserByChatId(chatId) } returns null
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returnsMany listOf(200L, 201L, 202L)
            every { createHouseholdUseCase.create(chatId, "sheet-x") } returns CreateHouseholdUseCase.Result.AlreadyInHousehold

            feed(textUpdate(chatId, "Создать таблицу", messageId = 1))
            feed(textUpdate(chatId, "sheet-x", messageId = 2, replyTo = botReplyPrompt(200)))

            verify(exactly = 1) { createHouseholdUseCase.create(chatId, "sheet-x") }

            // State cleared: another reply to promptId=200 is not re-treated as a step (no second use-case call).
            feed(textUpdate(chatId, "sheet-y", messageId = 3, replyTo = botReplyPrompt(200)))
            verify(exactly = 1) { createHouseholdUseCase.create(any(), any()) }
        }

        @Test
        fun `use case exception clears state and sends the failure reply`() {
            val chatId = 7007L
            every { householdRepository.findUserByChatId(chatId) } returns null
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returnsMany listOf(200L, 201L, 202L)
            every { createHouseholdUseCase.create(chatId, "sheet-x") } throws
                RuntimeException("Sheets permission denied")

            feed(textUpdate(chatId, "Создать таблицу", messageId = 1))
            feed(textUpdate(chatId, "sheet-x", messageId = 2, replyTo = botReplyPrompt(200)))

            verify(exactly = 1) { createHouseholdUseCase.create(chatId, "sheet-x") }

            // State cleared after the exception — further replies to the same prompt id are ignored.
            feed(textUpdate(chatId, "sheet-y", messageId = 3, replyTo = botReplyPrompt(200)))
            verify(exactly = 1) { createHouseholdUseCase.create(any(), any()) }
        }

        @Test
        fun `cancel mid-wizard clears state and skips the use case`() {
            val chatId = 7008L
            every { householdRepository.findUserByChatId(chatId) } returns null
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returnsMany listOf(200L, 201L, 202L)

            feed(textUpdate(chatId, "Создать таблицу", messageId = 1))
            feed(textUpdate(chatId, "Забей", messageId = 2, replyTo = botReplyPrompt(200)))

            // Cancel path never touches the use case…
            verify(exactly = 0) { createHouseholdUseCase.create(any(), any()) }
            // …and a later reply to the same prompt id is no longer a step.
            feed(textUpdate(chatId, "sheet-x", messageId = 3, replyTo = botReplyPrompt(200)))
            verify(exactly = 0) { createHouseholdUseCase.create(any(), any()) }
        }
    }

    @Nested
    inner class AddCardWizard {

        @Test
        fun `trigger sends bank picker keyboard and saves AwaitingBankChoice state`() {
            val chatId = 8001L
            registerUser(chatId)
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returns 300L

            feed(textUpdate(chatId, "Привязать карту", messageId = 1))

            // Keyboard MUST be present (bank picker); router will thread state via the returned messageId.
            verify(exactly = 1) {
                gateway.send(
                    chatId = chatId,
                    text = any(),
                    keyboard = match { it != null },
                    replyToMessageId = 1,
                )
            }
        }

        @Test
        fun `bank picker choose Mono asks for token and transitions to Mono AwaitingToken`() {
            val chatId = 8002L
            registerUser(chatId)
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returnsMany listOf(300L, 301L)

            feed(textUpdate(chatId, "Привязать карту", messageId = 1))
            feed(callbackUpdate(chatId, data = "b|mono", attachedMessageId = 300))

            verify(exactly = 1) { gateway.editKeyboard(chatId, 300L, null) }
            verify(exactly = 1) { gateway.answerCallback("cb-300", null) }
            // Second gateway.send (after the trigger) is the mono-token prompt threaded under the picker.
            verify(exactly = 1) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = 300) }
        }

        @Test
        fun `bank picker choose Privat with no existing account registers a new privat email link`() {
            val chatId = 8003L
            val (user, _) = registerUser(chatId)
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returns 300L
            every { addBankAccountUseCase.findFirstPrivatForUser(user) } returns null

            feed(textUpdate(chatId, "Привязать карту", messageId = 1))
            feed(callbackUpdate(chatId, data = "b|privat", attachedMessageId = 300))

            // Persistence happened with PRIVATBANK and a freshly-generated suffix; instructions sent.
            verify(exactly = 1) {
                addBankAccountUseCase.add(
                    user = user,
                    bankType = BankType.PRIVATBANK,
                    token = "",
                    accountId = any(),
                    clientId = null,
                )
            }
        }

        @Test
        fun `bank picker choose Privat with an existing account just resends instructions (no new persistence)`() {
            val chatId = 8004L
            val (user, _) = registerUser(chatId)
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returns 300L
            every { addBankAccountUseCase.findFirstPrivatForUser(user) } returns BankAccount(
                id = UUID.randomUUID(),
                userId = user.id,
                bankType = BankType.PRIVATBANK,
                token = "",
                accountId = "existing-suffix",
                clientId = null,
            )

            feed(textUpdate(chatId, "Привязать карту", messageId = 1))
            feed(callbackUpdate(chatId, data = "b|privat", attachedMessageId = 300))

            verify(exactly = 0) { addBankAccountUseCase.add(any(), any(), any(), any(), any()) }
        }

        @Test
        fun `bank picker with unknown choice clears state and reports bankNotFound`() {
            val chatId = 8005L
            registerUser(chatId)
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returnsMany listOf(300L, 301L)

            feed(textUpdate(chatId, "Привязать карту", messageId = 1))
            feed(callbackUpdate(chatId, data = "b|other", attachedMessageId = 300))

            // No further wizard actions — a subsequent Mono-token-shaped update must not persist anything.
            feed(textUpdate(chatId, "some-token", messageId = 2, replyTo = botReplyPrompt(300)))
            verify(exactly = 0) { addBankAccountUseCase.add(any(), any(), any(), any(), any()) }
            verify(exactly = 0) { monobankApi.getClientInfo(any()) }
        }

        @Test
        fun `bank picker callback whose attached-message id does not match state is a no-op except answerCallback`() {
            val chatId = 8006L
            registerUser(chatId)
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returns 300L

            feed(textUpdate(chatId, "Привязать карту", messageId = 1))
            // A stale/duplicate callback on a different message id (e.g. an old picker) — must not
            // touch the keyboard of the current prompt or start the Mono/Privat branches.
            feed(callbackUpdate(chatId, data = "b|mono", attachedMessageId = 999))

            verify(exactly = 1) { gateway.answerCallback("cb-999", any()) }
            verify(exactly = 0) { gateway.editKeyboard(any(), any(), any()) }
            verify(exactly = 0) { monobankApi.getClientInfo(any()) }
        }

        @Test
        fun `mono token step with empty token re-prompts, staying in AwaitingToken`() {
            val chatId = 8007L
            registerUser(chatId)
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returnsMany listOf(300L, 301L, 302L)

            feed(textUpdate(chatId, "Привязать карту", messageId = 1))
            feed(callbackUpdate(chatId, data = "b|mono", attachedMessageId = 300))
            feed(textUpdate(chatId, "", messageId = 2, replyTo = botReplyPrompt(301)))

            verify(exactly = 0) { monobankApi.getClientInfo(any()) }
            verify(exactly = 0) { addBankAccountUseCase.add(any(), any(), any(), any(), any()) }
        }

        @Test
        fun `mono token step with API failure clears state and reports tokenFailed`() {
            val chatId = 8008L
            registerUser(chatId)
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returnsMany listOf(300L, 301L, 302L, 303L)
            every { monobankApi.getClientInfo("bad-token") } throws RuntimeException("401 Unauthorized")

            feed(textUpdate(chatId, "Привязать карту", messageId = 1))
            feed(callbackUpdate(chatId, data = "b|mono", attachedMessageId = 300))
            feed(textUpdate(chatId, "bad-token", messageId = 2, replyTo = botReplyPrompt(301)))

            verify(exactly = 1) { monobankApi.getClientInfo("bad-token") }
            verify(exactly = 0) { addBankAccountUseCase.add(any(), any(), any(), any(), any()) }
            // State cleared — replying to the same prompt id again is ignored.
            feed(textUpdate(chatId, "another-token", messageId = 3, replyTo = botReplyPrompt(301)))
            verify(exactly = 1) { monobankApi.getClientInfo(any()) }
        }

        @Test
        fun `mono token step with zero accounts clears state and reports noAccounts`() {
            val chatId = 8009L
            registerUser(chatId)
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returnsMany listOf(300L, 301L, 302L)
            every { monobankApi.getClientInfo("ok-token") } returns MonoApiClientInfo(accounts = emptyList())

            feed(textUpdate(chatId, "Привязать карту", messageId = 1))
            feed(callbackUpdate(chatId, data = "b|mono", attachedMessageId = 300))
            feed(textUpdate(chatId, "ok-token", messageId = 2, replyTo = botReplyPrompt(301)))

            verify(exactly = 1) { monobankApi.getClientInfo("ok-token") }
            verify(exactly = 0) { addBankAccountUseCase.add(any(), any(), any(), any(), any()) }
        }

        @Test
        fun `mono token step with exactly one account persists it immediately`() {
            val chatId = 8010L
            val (user, _) = registerUser(chatId)
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returnsMany listOf(300L, 301L, 302L)
            every { monobankApi.getClientInfo("ok-token") } returns MonoApiClientInfo(
                accounts = listOf(MonoApiAccount(id = "acct-only", currencyCode = 980)),
            )

            feed(textUpdate(chatId, "Привязать карту", messageId = 1))
            feed(callbackUpdate(chatId, data = "b|mono", attachedMessageId = 300))
            feed(textUpdate(chatId, "ok-token", messageId = 2, replyTo = botReplyPrompt(301)))

            verify(exactly = 1) {
                addBankAccountUseCase.add(
                    user = user,
                    bankType = BankType.MONOBANK,
                    token = "ok-token",
                    accountId = "acct-only",
                    clientId = null,
                )
            }
        }

        @Test
        fun `mono token step with multiple accounts sends an account-picker keyboard and moves to AwaitingAccountChoice`() {
            val chatId = 8011L
            registerUser(chatId)
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returnsMany listOf(300L, 301L, 302L)
            every { monobankApi.getClientInfo("ok-token") } returns MonoApiClientInfo(
                accounts = listOf(
                    MonoApiAccount(id = "acct-a", currencyCode = 980, iban = "UA000000000001111"),
                    MonoApiAccount(id = "acct-b", currencyCode = 840, iban = "UA000000000002222"),
                ),
            )

            feed(textUpdate(chatId, "Привязать карту", messageId = 1))
            feed(callbackUpdate(chatId, data = "b|mono", attachedMessageId = 300))
            feed(textUpdate(chatId, "ok-token", messageId = 2, replyTo = botReplyPrompt(301)))

            // No persistence yet — we hand off to the picker.
            verify(exactly = 0) { addBankAccountUseCase.add(any(), any(), any(), any(), any()) }
            // Picker prompt has a keyboard and is threaded under the user's token reply.
            verify(exactly = 1) {
                gateway.send(
                    chatId = chatId,
                    text = any(),
                    keyboard = match { it != null },
                    replyToMessageId = 2,
                )
            }
        }

        @Test
        fun `mono account choice callback with a valid pick persists the chosen accountId`() {
            val chatId = 8012L
            val (user, _) = registerUser(chatId)
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returnsMany listOf(300L, 301L, 302L)
            every { monobankApi.getClientInfo("ok-token") } returns MonoApiClientInfo(
                accounts = listOf(
                    MonoApiAccount(id = "acct-a", currencyCode = 980),
                    MonoApiAccount(id = "acct-b", currencyCode = 840),
                ),
            )

            feed(textUpdate(chatId, "Привязать карту", messageId = 1))
            feed(callbackUpdate(chatId, data = "b|mono", attachedMessageId = 300))
            feed(textUpdate(chatId, "ok-token", messageId = 2, replyTo = botReplyPrompt(301)))
            // Now in AwaitingAccountChoice at promptId=302
            feed(callbackUpdate(chatId, data = "m|acct-b", attachedMessageId = 302))

            verify(exactly = 1) {
                addBankAccountUseCase.add(
                    user = user,
                    bankType = BankType.MONOBANK,
                    token = "ok-token",
                    accountId = "acct-b",
                    clientId = null,
                )
            }
        }

        @Test
        fun `mono account choice callback for an accountId not in the state's knownAccountIds is a no-op except answerCallback`() {
            val chatId = 8013L
            registerUser(chatId)
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returnsMany listOf(300L, 301L, 302L)
            every { monobankApi.getClientInfo("ok-token") } returns MonoApiClientInfo(
                accounts = listOf(
                    MonoApiAccount(id = "acct-a", currencyCode = 980),
                    MonoApiAccount(id = "acct-b", currencyCode = 840),
                ),
            )

            feed(textUpdate(chatId, "Привязать карту", messageId = 1))
            feed(callbackUpdate(chatId, data = "b|mono", attachedMessageId = 300))
            feed(textUpdate(chatId, "ok-token", messageId = 2, replyTo = botReplyPrompt(301)))
            feed(callbackUpdate(chatId, data = "m|not-a-real-account", attachedMessageId = 302))

            verify(exactly = 0) { addBankAccountUseCase.add(any(), any(), any(), any(), any()) }
        }

        @Test
        fun `cancel mid-wizard (in Mono AwaitingToken) clears state and skips the mono api`() {
            val chatId = 8014L
            registerUser(chatId)
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returnsMany listOf(300L, 301L, 302L)

            feed(textUpdate(chatId, "Привязать карту", messageId = 1))
            feed(callbackUpdate(chatId, data = "b|mono", attachedMessageId = 300))
            feed(textUpdate(chatId, "Забей", messageId = 2, replyTo = botReplyPrompt(301)))

            verify(exactly = 0) { monobankApi.getClientInfo(any()) }
            verify(exactly = 0) { addBankAccountUseCase.add(any(), any(), any(), any(), any()) }
            // State cleared — a subsequent reply to the same prompt id is ignored.
            feed(textUpdate(chatId, "would-be-token", messageId = 3, replyTo = botReplyPrompt(301)))
            verify(exactly = 0) { monobankApi.getClientInfo(any()) }
        }
    }

    @Nested
    inner class CashEntryWizard {

        @Test
        fun `trigger asks for amount and saves AwaitingAmount state`() {
            val chatId = 9001L
            registerUser(chatId)
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returns 400L

            feed(textUpdate(chatId, "Наличка", messageId = 1))

            verify(exactly = 1) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = 1) }
            verify(exactly = 0) { addCashTransactionUseCase.add(any(), any()) }
        }

        @Test
        fun `non-numeric amount re-prompts, stays in AwaitingAmount`() {
            val chatId = 9002L
            registerUser(chatId)
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returnsMany listOf(400L, 401L)

            feed(textUpdate(chatId, "Наличка", messageId = 1))
            feed(textUpdate(chatId, "abc", messageId = 2, replyTo = botReplyPrompt(400)))

            verify(exactly = 0) { addCashTransactionUseCase.add(any(), any()) }
            // start + badAmount re-prompt = 2 sends
            verify(exactly = 2) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = any()) }
        }

        @Test
        fun `zero amount is treated as invalid and re-prompts`() {
            val chatId = 9003L
            registerUser(chatId)
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returnsMany listOf(400L, 401L)

            feed(textUpdate(chatId, "Наличка", messageId = 1))
            feed(textUpdate(chatId, "0", messageId = 2, replyTo = botReplyPrompt(400)))

            verify(exactly = 0) { addCashTransactionUseCase.add(any(), any()) }
        }

        @Test
        fun `negative amount is treated as invalid and re-prompts`() {
            val chatId = 9004L
            registerUser(chatId)
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returnsMany listOf(400L, 401L)

            feed(textUpdate(chatId, "Наличка", messageId = 1))
            feed(textUpdate(chatId, "-42.50", messageId = 2, replyTo = botReplyPrompt(400)))

            verify(exactly = 0) { addCashTransactionUseCase.add(any(), any()) }
        }

        @Test
        fun `comma-decimal amount is parsed as a dot-decimal and passed to the use case`() {
            val chatId = 9005L
            val (_, household) = registerUser(chatId)
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returnsMany listOf(400L, 401L)
            every { householdRepository.findUsersInHousehold(household.id) } returns emptyList() // sendTx side-effect no-op
            every { addCashTransactionUseCase.add(household.id, 50.25) } returns tx(id = "cash-1", householdId = household.id)

            feed(textUpdate(chatId, "Наличка", messageId = 1))
            feed(textUpdate(chatId, "50,25", messageId = 2, replyTo = botReplyPrompt(400)))

            verify(exactly = 1) { addCashTransactionUseCase.add(household.id, 50.25) }
        }

        @Test
        fun `valid amount completes the wizard and broadcasts a categorisation prompt to all household users`() {
            val chatId = 9006L
            val (_, household) = registerUser(chatId)
            val otherChatId = 9099L
            // The user who typed /cash + one other household member.
            every { householdRepository.findUsersInHousehold(household.id) } returns listOf(
                botUser(chatId, household.id),
                botUser(otherChatId, household.id),
            )
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returnsMany listOf(400L, 401L, 500L)
            every { gateway.send(chatId = otherChatId, text = any(), keyboard = any(), replyToMessageId = any()) } returns 501L
            every { addCashTransactionUseCase.add(household.id, 42.0) } returns tx(id = "cash-2", householdId = household.id)

            feed(textUpdate(chatId, "Наличка", messageId = 1))
            feed(textUpdate(chatId, "42", messageId = 2, replyTo = botReplyPrompt(400)))

            // Cash transaction persisted, then the categorisation prompt (keyboard!) fanned out to BOTH members.
            verify(exactly = 1) { addCashTransactionUseCase.add(household.id, 42.0) }
            verify(exactly = 1) {
                gateway.send(
                    chatId = chatId,
                    text = any(),
                    keyboard = match { it != null },
                    replyToMessageId = null,
                )
            }
            verify(exactly = 1) {
                gateway.send(
                    chatId = otherChatId,
                    text = any(),
                    keyboard = match { it != null },
                    replyToMessageId = null,
                )
            }
        }

        @Test
        fun `use case exception clears state and reports cash_failed`() {
            val chatId = 9007L
            val (_, household) = registerUser(chatId)
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returnsMany listOf(400L, 401L, 402L)
            every { addCashTransactionUseCase.add(household.id, 42.0) } throws IllegalStateException("db down")

            feed(textUpdate(chatId, "Наличка", messageId = 1))
            feed(textUpdate(chatId, "42", messageId = 2, replyTo = botReplyPrompt(400)))

            verify(exactly = 1) { addCashTransactionUseCase.add(household.id, 42.0) }

            // State cleared — a later reply to the same prompt id is not re-treated as a step.
            feed(textUpdate(chatId, "100", messageId = 3, replyTo = botReplyPrompt(400)))
            verify(exactly = 1) { addCashTransactionUseCase.add(any(), any()) }
        }

        @Test
        fun `cancel mid-wizard clears state and skips the use case`() {
            val chatId = 9008L
            registerUser(chatId)
            every { gateway.send(chatId = chatId, text = any(), keyboard = any(), replyToMessageId = any()) } returnsMany listOf(400L, 401L, 402L)

            feed(textUpdate(chatId, "Наличка", messageId = 1))
            feed(textUpdate(chatId, "Забей", messageId = 2, replyTo = botReplyPrompt(400)))

            verify(exactly = 0) { addCashTransactionUseCase.add(any(), any()) }
            // A subsequent reply to the same prompt id is ignored.
            feed(textUpdate(chatId, "42", messageId = 3, replyTo = botReplyPrompt(400)))
            verify(exactly = 0) { addCashTransactionUseCase.add(any(), any()) }
        }
    }

    @Nested
    inner class CategorisationCallback {

        @Test
        fun `malformed callback data replies with badCallback and does NOT invoke onDecision`() {
            val chatId = 10001L
            registerUser(chatId)

            feed(callbackUpdate(chatId, data = "c|only-two-parts", attachedMessageId = 700))

            verify(exactly = 1) { gateway.answerCallback("cb-700", any()) }
            verify(exactly = 0) { onDecision(any(), any()) }
        }

        @Test
        fun `sheet row = -1 dispatches Decision Ignore, clears keyboards on ALL household prompts, and sends the ignored log`() {
            val chatId = 10002L
            val (_, household) = registerUser(chatId)
            val tx = tx(id = "tx-A", householdId = household.id)
            every { transactionStatusRepository.findByTransactionId("tx-A") } returns null
            every { telegramLogMessageRepository.findAllByTransactionId("tx-A") } returns listOf(
                logRow(household.id, chatId = chatId, messageId = 500L, transactionId = "tx-A"),
                logRow(household.id, chatId = 10099L, messageId = 501L, transactionId = "tx-A"),
            )
            // sendLogForDecision path
            every { transactionRepository.get("tx-A") } returns Optional.of(tx)
            every { householdRepository.findHousehold(household.id) } returns household
            every { householdRepository.findUsersInHousehold(household.id) } returns emptyList()

            feed(callbackUpdate(chatId, data = "c|tx-A|-1", attachedMessageId = 700))

            verify(exactly = 1) { onDecision("tx-A", CategorizationBot.Decision.Ignore) }
            verify(exactly = 1) { gateway.editKeyboard(chatId, 500L, null) }
            verify(exactly = 1) { gateway.editKeyboard(10099L, 501L, null) }
            verify(exactly = 1) { gateway.answerCallback("cb-700", any()) }
        }

        @Test
        fun `valid sheet row dispatches Decision Category with the resolved categoryId`() {
            val chatId = 10003L
            val (_, household) = registerUser(chatId)
            val chosen = category(household.id, "Coffee").copy(sheetRow = 7)
            every { transactionStatusRepository.findByTransactionId("tx-B") } returns null
            every { categoryRepository.findBySheetRow(household.id, 7) } returns chosen
            every { telegramLogMessageRepository.findAllByTransactionId("tx-B") } returns emptyList()
            every { transactionRepository.get("tx-B") } returns Optional.of(tx(id = "tx-B", householdId = household.id))
            every { householdRepository.findHousehold(household.id) } returns household
            every { householdRepository.findUsersInHousehold(household.id) } returns emptyList()

            feed(callbackUpdate(chatId, data = "c|tx-B|7", attachedMessageId = 700))

            verify(exactly = 1) { onDecision("tx-B", CategorizationBot.Decision.Category(chosen.id)) }
        }

        @Test
        fun `unknown sheet row (category lookup returns null) replies with badCallback and skips onDecision`() {
            val chatId = 10004L
            val (_, household) = registerUser(chatId)
            every { categoryRepository.findBySheetRow(household.id, 999) } returns null

            feed(callbackUpdate(chatId, data = "c|tx-C|999", attachedMessageId = 700))

            verify(exactly = 0) { onDecision(any(), any()) }
            verify(exactly = 1) { gateway.answerCallback("cb-700", any()) }
        }

        @Test
        fun `already EXECUTED transaction acks with alreadyDone, strips the tapper's keyboard, no onDecision`() {
            val chatId = 10005L
            val (_, household) = registerUser(chatId)
            val chosen = category(household.id, "Coffee").copy(sheetRow = 7)
            every { categoryRepository.findBySheetRow(household.id, 7) } returns chosen
            every { transactionStatusRepository.findByTransactionId("tx-D") } returns TransactionStatus.EXECUTED

            feed(callbackUpdate(chatId, data = "c|tx-D|7", attachedMessageId = 700))

            verify(exactly = 0) { onDecision(any(), any()) }
            verify(exactly = 1) { gateway.editKeyboard(chatId, 700L, null) }
            verify(exactly = 1) { gateway.answerCallback("cb-700", any()) }
        }

        @Test
        fun `already IGNORED transaction is also treated as final (no onDecision)`() {
            val chatId = 10006L
            val (_, household) = registerUser(chatId)
            val chosen = category(household.id, "Coffee").copy(sheetRow = 7)
            every { categoryRepository.findBySheetRow(household.id, 7) } returns chosen
            every { transactionStatusRepository.findByTransactionId("tx-E") } returns TransactionStatus.IGNORED

            feed(callbackUpdate(chatId, data = "c|tx-E|7", attachedMessageId = 700))

            verify(exactly = 0) { onDecision(any(), any()) }
        }

        @Test
        fun `callback from an unregistered chat is refused with notAllowed and never reaches the handler`() {
            val chatId = 10007L
            every { householdRepository.findUserByChatId(chatId) } returns null

            feed(callbackUpdate(chatId, data = "c|tx-F|-1", attachedMessageId = 700))

            verify(exactly = 1) { gateway.answerCallback("cb-700", any()) }
            verify(exactly = 0) { onDecision(any(), any()) }
        }
    }

    @Nested
    inner class SaveKeywordCallback {

        @Test
        fun `malformed data replies with badCallback`() {
            val chatId = 11001L
            registerUser(chatId)

            feed(callbackUpdate(chatId, data = "k|missing-row", attachedMessageId = 800))

            verify(exactly = 1) { gateway.answerCallback("cb-800", any()) }
            verify(exactly = 0) { saveKeywordUseCase(any(), any()) }
        }

        @Test
        fun `unknown transaction id replies with txNotFound`() {
            val chatId = 11002L
            val (_, household) = registerUser(chatId)
            val chosen = category(household.id, "Coffee").copy(sheetRow = 7)
            every { categoryRepository.findBySheetRow(household.id, 7) } returns chosen
            every { transactionRepository.get("tx-Z") } returns Optional.empty()

            feed(callbackUpdate(chatId, data = "k|tx-Z|7", attachedMessageId = 800))

            verify(exactly = 0) { saveKeywordUseCase(any(), any()) }
            verify(exactly = 1) { gateway.answerCallback("cb-800", any()) }
        }

        @Test
        fun `Saved result strips keyboard, acks with saved, and sends success message`() {
            val chatId = 11003L
            val (_, household) = registerUser(chatId)
            val chosen = category(household.id, "Coffee").copy(sheetRow = 7)
            every { categoryRepository.findBySheetRow(household.id, 7) } returns chosen
            every { transactionRepository.get("tx-S") } returns Optional.of(tx(id = "tx-S", householdId = household.id, description = "Coffee shop"))
            every { saveKeywordUseCase(chosen.id, "Coffee shop") } returns SaveKeywordUseCase.Result.Saved(chosen, "Coffee\\ shop")

            feed(callbackUpdate(chatId, data = "k|tx-S|7", attachedMessageId = 800))

            verify(exactly = 1) { saveKeywordUseCase(chosen.id, "Coffee shop") }
            verify(exactly = 1) { gateway.editKeyboard(chatId, 800L, null) }
            verify(exactly = 1) { gateway.answerCallback("cb-800", any()) }
            // Follow-up plain message to the same chat confirming success.
            verify(atLeast = 1) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = null) }
        }

        @Test
        fun `AlreadyPresent result acks with alreadyPresent and sends the notice`() {
            val chatId = 11004L
            val (_, household) = registerUser(chatId)
            val chosen = category(household.id, "Coffee").copy(sheetRow = 7)
            every { categoryRepository.findBySheetRow(household.id, 7) } returns chosen
            every { transactionRepository.get("tx-P") } returns Optional.of(tx(id = "tx-P", householdId = household.id, description = "Coffee"))
            every { saveKeywordUseCase(chosen.id, "Coffee") } returns SaveKeywordUseCase.Result.AlreadyPresent(chosen, "Coffee")

            feed(callbackUpdate(chatId, data = "k|tx-P|7", attachedMessageId = 800))

            verify(exactly = 1) { gateway.answerCallback("cb-800", any()) }
            verify(atLeast = 1) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = null) }
        }

        @Test
        fun `CategoryNotFound result acks with categoryNotFound`() {
            val chatId = 11005L
            val (_, household) = registerUser(chatId)
            val chosen = category(household.id, "Coffee").copy(sheetRow = 7)
            every { categoryRepository.findBySheetRow(household.id, 7) } returns chosen
            every { transactionRepository.get("tx-N") } returns Optional.of(tx(id = "tx-N", householdId = household.id, description = "d"))
            every { saveKeywordUseCase(chosen.id, "d") } returns SaveKeywordUseCase.Result.CategoryNotFound

            feed(callbackUpdate(chatId, data = "k|tx-N|7", attachedMessageId = 800))

            verify(exactly = 1) { gateway.answerCallback("cb-800", any()) }
        }

        @Test
        fun `EmptyKeyword result acks with empty`() {
            val chatId = 11006L
            val (_, household) = registerUser(chatId)
            val chosen = category(household.id, "Coffee").copy(sheetRow = 7)
            every { categoryRepository.findBySheetRow(household.id, 7) } returns chosen
            every { transactionRepository.get("tx-M") } returns Optional.of(tx(id = "tx-M", householdId = household.id, description = ""))
            every { saveKeywordUseCase(chosen.id, "") } returns SaveKeywordUseCase.Result.EmptyKeyword

            feed(callbackUpdate(chatId, data = "k|tx-M|7", attachedMessageId = 800))

            verify(exactly = 1) { gateway.answerCallback("cb-800", any()) }
        }
    }

    @Nested
    inner class CommentAndSaveKeywordReply {

        @Test
        fun `reply with comment text delegates to HandleTelegramCommentUseCase and confirms saved`() {
            val chatId = 12001L
            registerUser(chatId)
            every { handleTelegramCommentUseCase(chatId, 500L, "great restaurant") } returns true

            feed(textUpdate(chatId, "great restaurant", messageId = 30, replyTo = botReplyPrompt(500)))

            verify(exactly = 1) { handleTelegramCommentUseCase(chatId, 500L, "great restaurant") }
            verify(exactly = 1) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = 30) }
        }

        @Test
        fun `use case returning false triggers the not-found reply`() {
            val chatId = 12002L
            registerUser(chatId)
            every { handleTelegramCommentUseCase(chatId, 500L, "orphan comment") } returns false

            feed(textUpdate(chatId, "orphan comment", messageId = 31, replyTo = botReplyPrompt(500)))

            verify(exactly = 1) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = 31) }
        }

        @Test
        fun `use case throwing triggers the save-error reply`() {
            val chatId = 12003L
            registerUser(chatId)
            every { handleTelegramCommentUseCase(chatId, 500L, "boom") } throws RuntimeException("db down")

            feed(textUpdate(chatId, "boom", messageId = 32, replyTo = botReplyPrompt(500)))

            verify(exactly = 1) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = 32) }
        }

        @Test
        fun `saveKeyword trigger reply routes to promptSaveKeywordCategory and sends a category keyboard`() {
            val chatId = 12004L
            val (_, household) = registerUser(chatId)
            val record = logRow(household.id, chatId = chatId, messageId = 500L, transactionId = "tx-K")
            every { telegramLogMessageRepository.findByChatAndMessage(chatId, 500L) } returns record
            every { transactionRepository.get("tx-K") } returns Optional.of(tx(id = "tx-K", householdId = household.id, description = "coffee house"))

            feed(textUpdate(chatId, "Сохранить", messageId = 40, replyTo = botReplyPrompt(500)))

            // Keyboard sent with the save-keyword category picker; comment use case NOT invoked (this is the trigger path).
            verify(exactly = 0) { handleTelegramCommentUseCase(any(), any(), any()) }
            verify(exactly = 1) {
                gateway.send(
                    chatId = chatId,
                    text = any(),
                    keyboard = match { it != null },
                    replyToMessageId = null,
                )
            }
        }

        @Test
        fun `saveKeyword trigger reply for an unknown message id sends txMissing`() {
            val chatId = 12005L
            registerUser(chatId)
            every { telegramLogMessageRepository.findByChatAndMessage(chatId, 500L) } returns null

            feed(textUpdate(chatId, "Сохранить", messageId = 41, replyTo = botReplyPrompt(500)))

            // Plain (no-keyboard) notice; no category picker.
            verify(exactly = 0) {
                gateway.send(
                    chatId = any(),
                    text = any(),
                    keyboard = match { it != null },
                    replyToMessageId = any(),
                )
            }
            verify(exactly = 1) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = null) }
        }

        @Test
        fun `saveKeyword trigger reply on a cash transaction is rejected`() {
            val chatId = 12006L
            val (_, household) = registerUser(chatId)
            val record = logRow(household.id, chatId = chatId, messageId = 500L, transactionId = "cash-42")
            every { telegramLogMessageRepository.findByChatAndMessage(chatId, 500L) } returns record
            every { transactionRepository.get("cash-42") } returns Optional.of(tx(id = "cash-42", householdId = household.id, description = "Наличка"))

            feed(textUpdate(chatId, "Сохранить", messageId = 42, replyTo = botReplyPrompt(500)))

            // Cash rejected with a plain notice, no keyboard.
            verify(exactly = 0) {
                gateway.send(
                    chatId = any(),
                    text = any(),
                    keyboard = match { it != null },
                    replyToMessageId = any(),
                )
            }
            verify(exactly = 1) { gateway.send(chatId = chatId, text = any(), keyboard = null, replyToMessageId = null) }
        }
    }

    // ── fixtures ──

    private fun request(
        txId: String,
        householdId: UUID,
        transactionTime: String = "2026-07-09T12:00:00Z",
        description: String = "Coffee shop",
        amount: String = "50.00 UAH",
    ) = CategorizationRequest(
        transactionId = txId,
        householdId = householdId,
        transactionTime = transactionTime,
        description = description,
        amount = amount,
    )

    private fun tx(
        id: String,
        householdId: UUID,
        description: String = "Coffee shop",
        time: Long = 1_720_000_000L,
        amount: Long = -5000L,
        currencyCode: Int = 980,
    ) = Transaction(
        id = id,
        householdId = householdId,
        createdAt = time,
        description = description,
        time = time,
        amount = amount,
        currencyCode = currencyCode,
        comment = null,
        counterName = null,
    )

    private fun category(householdId: UUID, name: String) = Category(
        id = UUID.randomUUID(),
        householdId = householdId,
        name = name.uppercase(),
        displayName = name,
        sheetRow = 1,
        priority = 10,
        keywords = emptyList(),
        isOther = false,
    )

    private fun logRow(householdId: UUID, chatId: Long, messageId: Long, transactionId: String) =
        TelegramLogMessageJpaEntity(
            id = UUID.randomUUID(),
            householdId = householdId,
            chatId = chatId,
            messageId = messageId,
            transactionId = transactionId,
            comment = null,
        )

    private fun botUser(chatId: Long, householdId: UUID = UUID.randomUUID()) = BotUser(
        id = UUID.randomUUID(),
        chatId = chatId,
        name = "Test user",
        householdId = householdId,
    )

    /** Wire the mocks so [chatId] belongs to a registered user of a valid household. */
    private fun registerUser(chatId: Long): Pair<BotUser, Household> {
        val householdId = UUID.randomUUID()
        val user = botUser(chatId, householdId)
        val household = household(householdId)
        every { householdRepository.findUserByChatId(chatId) } returns user
        every { householdRepository.findHousehold(householdId) } returns household
        return user to household
    }

    /** A message posted by the bot itself (used as `replyTo` when the user answers a bot prompt). */
    private fun botReplyPrompt(messageId: Int): Message = mockk(relaxed = true) {
        every { messageId() } returns messageId
        every { from() } returns mockk(relaxed = true) {
            every { isBot } returns true
        }
    }

    private fun household(id: UUID) = Household(
        id = id,
        name = "Test household",
        sheetId = "sheet-$id",
        templateSheetTitle = "Template",
    )

    /**
     * Build a fake [Update] carrying an inline-keyboard callback. `attachedMessageId` is the id of
     * the bot message the button was attached to — matched by the router against wizard state's
     * `lastPromptMessageId`.
     */
    private fun callbackUpdate(
        chatId: Long,
        data: String,
        attachedMessageId: Int,
        callbackId: String = "cb-$attachedMessageId",
    ): Update {
        val attached = mockk<Message>(relaxed = true).apply {
            every { chat().id() } returns chatId
            every { messageId() } returns attachedMessageId
        }
        val callback = mockk<CallbackQuery>(relaxed = true).apply {
            every { id() } returns callbackId
            every { data() } returns data
            every { message() } returns attached
            every { from() } returns mockk(relaxed = true) {
                every { isBot } returns false
                every { firstName() } returns "Tester"
            }
        }
        return mockk<Update>(relaxed = true).apply {
            every { message() } returns null
            every { callbackQuery() } returns callback
        }
    }

    /**
     * Build a fake [Update] carrying a text [Message] with the given chat, text, and reply-to.
     * `replyTo` is either null (top-level message) or another [Message] fake — use [botReplyPrompt]
     * to make one that mimics a prompt the bot itself sent (`from().isBot == true`).
     */
    private fun textUpdate(
        chatId: Long,
        text: String,
        messageId: Int = 1,
        replyTo: Message? = null,
    ): Update {
        val message = mockk<Message>(relaxed = true).apply {
            every { chat().id() } returns chatId
            every { text() } returns text
            every { messageId() } returns messageId
            every { replyToMessage() } returns replyTo
            every { from() } returns mockk(relaxed = true) {
                every { isBot } returns false
            }
        }
        return mockk<Update>(relaxed = true).apply {
            every { message() } returns message
            every { callbackQuery() } returns null
        }
    }
}

@file:Suppress("SENSELESS_COMPARISON") // MockK `match { it != null }` on nullable params trips the compiler; the check is meaningful.

package MailAggregator.MailAggregator.telegram

import MailAggregator.MailAggregator.bank.Transaction
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
import MailAggregator.MailAggregator.monobank.api.MonobankApi
import MailAggregator.MailAggregator.spreadsheet.Authentication
import MailAggregator.MailAggregator.telegram.model.CategorizationRequest
import MailAggregator.MailAggregator.telegram.repository.TelegramLogMessageRepository
import MailAggregator.MailAggregator.telegram.repository.jpa.TelegramLogMessageJpaEntity
import io.mockk.every
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.context.support.ResourceBundleMessageSource
import java.time.ZoneId
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

    @BeforeEach
    fun setUp() {
        // buildKeyboard() requires at least one isOther=true category; every household has one in prod.
        every { categoryRepository.findAll(any()) } returns listOf(
            category(UUID.randomUUID(), "Other").copy(isOther = true, sheetRow = 999),
        )
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
}

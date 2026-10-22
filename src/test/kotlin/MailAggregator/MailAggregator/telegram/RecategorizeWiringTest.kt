package MailAggregator.MailAggregator.telegram

import MailAggregator.MailAggregator.household.BotUser
import MailAggregator.MailAggregator.telegram.wizard.MessageContext
import MailAggregator.MailAggregator.telegram.wizard.RecategorizeWizard
import MailAggregator.MailAggregator.telegram.wizard.Wizard
import com.pengrad.telegrambot.model.Message
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.MessageSource
import java.util.Locale
import java.util.UUID

/**
 * The unit tests build the wizard by hand with their own `ResourceBundleMessageSource`, so they
 * can't catch a trigger that fails to resolve through the *application's* MessageSource, or a
 * wizard that never makes it into the router's injected `List<Wizard>`. Both would leave «Удалить»
 * falling through to the comment flow with every unit test still green.
 */
@SpringBootTest
class RecategorizeWiringTest {

    @Autowired
    private lateinit var wizards: List<Wizard>

    @Autowired
    private lateinit var messageSource: MessageSource

    @Test
    fun `the recategorize wizard is one of the wizards the router receives`() {
        assertTrue(
            wizards.any { it is RecategorizeWizard },
            "RecategorizeWizard missing from the injected wizards: ${wizards.map { it::class.simpleName }}",
        )
    }

    @Test
    fun `the application message source resolves the trigger to Удалить`() {
        assertEquals("Удалить", messageSource.getMessage("trigger.recategorize", emptyArray(), Locale.ROOT))
    }

    @Test
    fun `the wired wizard claims a Удалить reply to a bot message`() {
        val wizard = wizards.first { it is RecategorizeWizard }
        val botMessage: Message = mockk(relaxed = true) {
            every { from() } returns mockk(relaxed = true) { every { isBot } returns true }
        }
        val context = MessageContext(
            chatId = 1L,
            msg = mockk(relaxed = true),
            text = "Удалить",
            replyTo = botMessage,
            user = BotUser(UUID.randomUUID(), 1L, "u", UUID.randomUUID()),
            household = null,
        )
        assertTrue(wizard.matchesStartTrigger(context), "the wired wizard did not claim the reply")
    }
}

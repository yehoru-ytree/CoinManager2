package MailAggregator.MailAggregator.privatbank.email

import MailAggregator.MailAggregator.bank.BankType
import MailAggregator.MailAggregator.bank.repository.BankAccountRepository
import MailAggregator.MailAggregator.household.repository.HouseholdRepository
import MailAggregator.MailAggregator.spreadsheet.usecases.ProcessIncomingBankTransactionsUseCase
import MailAggregator.MailAggregator.telegram.CategorizationBot
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeUtility
import jakarta.mail.search.AndTerm
import jakarta.mail.search.FlagTerm
import jakarta.mail.search.FromStringTerm
import jakarta.mail.search.OrTerm
import jakarta.mail.search.SearchTerm
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.util.Properties

/**
 * Polls our dedicated Gmail ingest inbox for two things:
 *  - PrivatBank's transaction notifications (`info@pb.ua`) — parsed via [PrivatEmailParser],
 *    routed to the right household by the `To: ...+suffix@…` alias, and fed through the same
 *    pipeline as the polling job.
 *  - Gmail's forwarding-confirmation emails (`forwarding-noreply@google.com`) — the verification
 *    URL is GET-ed automatically so new users don't have to copy a code by hand.
 *
 * Other senders are left unread for manual inspection (and won't be re-polled until newer mail
 * arrives — defaults to UNSEEN-only search).
 */
@Component
class PrivatEmailIngestor(
    @Value("\${email.imap.host}") private val host: String,
    @Value("\${email.imap.port:993}") private val port: Int,
    @Value("\${email.imap.user}") private val username: String,
    @Value("\${email.imap.password}") private val password: String,
    @Value("\${email.imap.folder:INBOX}") private val folderName: String,
    private val bankAccountRepository: BankAccountRepository,
    private val householdRepository: HouseholdRepository,
    private val processIncomingBankTransactionsUseCase: ProcessIncomingBankTransactionsUseCase,
    private val categorizationBot: CategorizationBot,
) {

    @Scheduled(fixedDelayString = "\${email.imap.poll-interval}")
    fun ingest() {
        if (username.isBlank() || password.isBlank()) return
        try {
            openStore().use { store ->
                val folder = store.getFolder(folderName)
                folder.open(Folder.READ_WRITE)
                try {
                    val unseen = FlagTerm(jakarta.mail.Flags(jakarta.mail.Flags.Flag.SEEN), false)
                    val fromInteresting: SearchTerm = OrTerm(
                        FromStringTerm(PRIVAT_SENDER),
                        FromStringTerm(GMAIL_FORWARDING_SENDER),
                    )
                    val messages = folder.search(AndTerm(unseen, fromInteresting))
                    for (msg in messages) {
                        val processed = runCatching { handle(msg) }
                            .onFailure { println("Email ingest failed (subject='${msg.subject}'): ${it.message}") }
                            .getOrElse { false }
                        if (processed) msg.setFlag(jakarta.mail.Flags.Flag.SEEN, true)
                    }
                } finally {
                    folder.close(false)
                }
            }
        } catch (e: Exception) {
            println("PrivatEmailIngestor: poll failed: ${e.message}")
        }
    }

    /** Returns true iff the message was handled and should be marked SEEN. */
    private fun handle(msg: Message): Boolean {
        val from = (msg.from?.firstOrNull() as? InternetAddress)?.address?.lowercase() ?: return false
        return when {
            from == PRIVAT_SENDER -> handlePrivatTx(msg)
            from == GMAIL_FORWARDING_SENDER -> handleGmailForwardingConfirmation(msg)
            else -> false
        }
    }

    private fun handlePrivatTx(msg: Message): Boolean {
        val suffix = extractAliasSuffix(msg) ?: run {
            println("PrivatEmail: no recognisable alias suffix in To/Delivered-To; skipping")
            return false
        }
        val account = bankAccountRepository.findByTypeAndAccountId(BankType.PRIVATBANK, suffix) ?: run {
            println("PrivatEmail: no PRIVATBANK bank_account for suffix='$suffix'; skipping")
            return false
        }
        val user = householdRepository.findUserById(account.userId) ?: return false
        val household = householdRepository.findHousehold(user.householdId) ?: return false

        val body = extractTextBody(msg)
        val messageId = (msg.getHeader("Message-ID")?.firstOrNull() ?: "").trim('<', '>', ' ')
        val txId = if (messageId.isNotBlank()) "privat-email-$messageId" else "privat-email-${msg.subject}-${msg.sentDate?.time}"
        val txTimeSec = (msg.sentDate ?: msg.receivedDate)?.time?.let { it / 1000 } ?: (System.currentTimeMillis() / 1000)

        val tx = PrivatEmailParser.parse(body, household.id, txTimeSec, txId) ?: run {
            // Self-transfer or incoming: parser returned null. Still mark as seen — it's a known
            // pattern we explicitly chose to skip, no point re-reading next poll.
            return true
        }

        processIncomingBankTransactionsUseCase.processTransactionsForHousehold(household, listOf(tx))
        return true
    }

    private fun handleGmailForwardingConfirmation(msg: Message): Boolean {
        // Anonymous GET on the verification URL is blocked by Google (Temporary Error 2477) and
        // the localised body (Russian/Ukrainian) doesn't include a 'Confirmation code:' line at
        // all — only the URL. So forward the URL itself to the user via Telegram; clicking it
        // in their own browser (where they're logged in to Gmail) completes verification.
        val body = extractTextBody(msg)
        val url = GMAIL_APPROVE_URL.find(body)?.value ?: run {
            println("PrivatEmail: Gmail confirmation but no approve URL found. Body excerpt: ${body.take(500).replace(Regex("\\s+"), " ")}")
            return false
        }
        val suffix = extractAliasSuffix(msg) ?: run {
            println("PrivatEmail: Gmail confirmation but no recognisable alias suffix in headers")
            return false
        }
        val account = bankAccountRepository.findByTypeAndAccountId(BankType.PRIVATBANK, suffix) ?: run {
            println("PrivatEmail: Gmail confirmation for unknown suffix='$suffix'; ignoring")
            return false
        }
        val user = householdRepository.findUserById(account.userId) ?: return false
        try {
            categorizationBot.notifyChat(
                user.chatId,
                "Gmail прислал ссылку для подтверждения forwarding-адреса. " +
                    "Открой её в браузере, где ты залогинен в свой Gmail (форвардинг включится автоматически):\n\n" +
                    url,
            )
            println("PrivatEmail: relayed Gmail forwarding URL to chat=${user.chatId} (suffix=$suffix)")
        } catch (e: Exception) {
            println("PrivatEmail: failed to relay Gmail forwarding URL to chat=${user.chatId}: ${e.message}")
            return false
        }
        return true
    }

    private fun extractAliasSuffix(msg: Message): String? {
        val recipients = sequenceOf("To", "Delivered-To", "X-Forwarded-To")
            .flatMap { (msg.getHeader(it) ?: emptyArray()).asSequence() }
        val pattern = Regex("""${Regex.escape(ingestLocalPart())}\+([^@]+)@""")
        for (header in recipients) {
            val decoded = runCatching { MimeUtility.decodeText(header) }.getOrDefault(header)
            pattern.find(decoded)?.let { return it.groupValues[1] }
        }
        return null
    }

    private fun ingestLocalPart(): String = username.substringBefore('@')

    private fun openStore(): Store {
        val props = Properties().apply {
            setProperty("mail.store.protocol", "imaps")
            setProperty("mail.imaps.host", host)
            setProperty("mail.imaps.port", port.toString())
            setProperty("mail.imaps.ssl.enable", "true")
        }
        val session = Session.getInstance(props)
        return session.getStore("imaps").apply { connect(host, port, username, password) }
    }

    private fun extractTextBody(part: Part): String {
        val content = part.content
        return when {
            part.isMimeType("text/plain") -> content.toString()
            part.isMimeType("text/html") -> stripHtmlTags(content.toString())
            content is Multipart -> {
                // Prefer text/plain over text/html when both are present (multipart/alternative).
                val parts = (0 until content.count).map { content.getBodyPart(it) }
                val plain = parts.firstOrNull { it.isMimeType("text/plain") }
                val html = parts.firstOrNull { it.isMimeType("text/html") }
                val nested = parts.firstOrNull { it.isMimeType("multipart/*") }
                when {
                    plain != null -> extractTextBody(plain)
                    html != null -> extractTextBody(html)
                    nested != null -> extractTextBody(nested)
                    else -> parts.joinToString("\n") { extractTextBody(it) }
                }
            }
            else -> {
                val out = ByteArrayOutputStream()
                part.inputStream.copyTo(out)
                out.toString(Charsets.UTF_8)
            }
        }
    }

    private fun stripHtmlTags(html: String): String =
        html.replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("\\s+"), " ")
            .trim()

    private inline fun <T> Store.use(block: (Store) -> T): T {
        try {
            return block(this)
        } finally {
            runCatching { close() }
        }
    }

    companion object {
        private const val PRIVAT_SENDER = "info@pb.ua"
        private const val GMAIL_FORWARDING_SENDER = "forwarding-noreply@google.com"
        // Gmail's "approve forwarding" URL — `vf-...` is the verify prefix. The matching `uf-...`
        // is the cancel/unverify link which we deliberately do NOT forward.
        private val GMAIL_APPROVE_URL = Regex("""https://mail-settings\.google\.com/mail/vf-[\w%-]+""")
    }
}

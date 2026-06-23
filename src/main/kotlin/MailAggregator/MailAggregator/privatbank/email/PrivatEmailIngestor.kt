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
        if (username.isBlank() || password.isBlank()) {
            println("PrivatEmailIngestor: poll skipped — username/password blank")
            return
        }
        println("PrivatEmailIngestor: poll starting (host=$host, user=$username)")
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
                    if (messages.isNotEmpty()) {
                        // Force IMAP to pre-fetch envelope, flags, AND full headers in one round
                        // trip. By default `msg.getAllHeaders()` only returns envelope-level
                        // headers — X-Forwarded-To and friends arrive empty, which is exactly
                        // where Gmail-filter forwards stash the per-user alias suffix.
                        val profile = jakarta.mail.FetchProfile().apply {
                            add(jakarta.mail.FetchProfile.Item.ENVELOPE)
                            add(jakarta.mail.FetchProfile.Item.FLAGS)
                            add(jakarta.mail.FetchProfile.Item.CONTENT_INFO)
                            add("X-Forwarded-To")
                            add("X-Forwarded-For")
                            add("Delivered-To")
                            add("Return-Path")
                        }
                        folder.fetch(messages, profile)
                    }
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
        // Routing the message to a user: try the alias suffix first (works for cases where the
        // To: literally contains coinmanager.ingest+<suffix>@gmail.com — e.g. when Privat is
        // configured to send notifications directly to our alias). Otherwise fall back to the
        // forwarder Gmail address parsed from the Return-Path's CAF wrapper — that's what
        // Gmail-filter forwards always carry, even though they strip the +<suffix> from
        // Delivered-To.
        val suffix = extractAliasSuffix(msg)
        val forwarder = extractForwarderFromReturnPath(msg)
        val account = suffix
            ?.let { bankAccountRepository.findByTypeAndAccountId(BankType.PRIVATBANK, it) }
            ?: forwarder?.let { bankAccountRepository.findByTypeAndToken(BankType.PRIVATBANK, it) }
            ?: run {
                println("PrivatEmail: no PRIVATBANK match. suffix='$suffix', forwarder='$forwarder'; skipping")
                return false
            }
        val user = householdRepository.findUserById(account.userId) ?: return false
        val household = householdRepository.findHousehold(user.householdId) ?: return false

        val body = extractTextBody(msg)
        val messageId = (msg.getHeader("Message-ID")?.firstOrNull() ?: "").trim('<', '>', ' ')
        // Telegram callback_data caps at 64 bytes, and tx ids end up in "c|<id>|<row>" payloads
        // for the OTHER-flow inline keyboard. Hash the Message-ID instead of pasting it raw —
        // SHA-1 truncated to 12 hex chars is still globally unique enough for dedup against the
        // existing transactions table.
        val idSource = messageId.ifBlank { "${msg.subject ?: ""}-${msg.sentDate?.time ?: System.currentTimeMillis()}" }
        val hash = java.security.MessageDigest.getInstance("SHA-1")
            .digest(idSource.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(12)
        val txId = "privat-email-$hash"
        val txTimeSec = (msg.sentDate ?: msg.receivedDate)?.time?.let { it / 1000 } ?: (System.currentTimeMillis() / 1000)

        val tx = PrivatEmailParser.parse(body, household.id, txTimeSec, txId) ?: run {
            // Parser returns null for known-skip cases (self-transfer, incoming) AND for anything
            // it didn't recognise. We can't distinguish the two without colour-coding, so log the
            // body and DON'T mark seen — easier to spot a regex gap than to dig out the email
            // from Gmail later. Real self-transfers will keep re-logging until the parser is
            // taught to distinguish them as a positive skip.
            println("PrivatEmail: unparsed transaction email. Subject='${msg.subject}', body='${body.take(1000)}'")
            return false
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

        // Auto-link forwarder Gmail to this account by parsing the confirmation body. Gmail's
        // filter forwards lose the +<suffix> from Delivered-To (plus-addressing collapse on
        // Gmail-to-Gmail SMTP), so transaction emails get routed by forwarder address instead.
        val forwarder = extractForwarderFromConfirmationBody(body)
        if (forwarder != null && account.token != forwarder) {
            bankAccountRepository.update(account.copy(token = forwarder))
            println("PrivatEmail: linked forwarder='$forwarder' to suffix='$suffix' for chat=${user.chatId}")
        }

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

    // Confirmation body (Russian Gmail): "Мы получили запрос на автоматическую пересылку писем
    // с адреса egorusdnepr@gmail.com на нашу почту ...". English: "...request to forward mail
    // from egorusdnepr@gmail.com to your address...". Match either.
    private fun extractForwarderFromConfirmationBody(body: String): String? =
        FORWARDER_IN_BODY.find(body)?.groupValues?.get(1)?.let(::canonicaliseGmail)

    // Return-Path on Gmail-filter forwarded mail looks like:
    //   <egorusdnepr+caf_=coinmanager.ingest=gmail.com@gmail.com>
    // The forwarder's base address is the local-part BEFORE `+caf_=`, at gmail.com.
    private fun extractForwarderFromReturnPath(msg: Message): String? {
        val mime = msg as? jakarta.mail.internet.MimeMessage ?: return null
        val returnPath = mime.allHeaderLines.toList().firstOrNull { it.startsWith("Return-Path:", ignoreCase = true) } ?: return null
        return RETURN_PATH_FORWARDER.find(returnPath)?.let { canonicaliseGmail("${it.groupValues[1]}@gmail.com") }
    }

    private fun canonicaliseGmail(addr: String): String {
        val lower = addr.lowercase()
        val (local, domain) = lower.split('@', limit = 2).let {
            if (it.size != 2) return lower else it[0] to it[1]
        }
        return "${local.substringBefore('+')}@$domain"
    }

    private fun extractAliasSuffix(msg: Message): String? {
        val pattern = Regex("""${Regex.escape(ingestLocalPart())}\+([^@]+)@""", RegexOption.IGNORE_CASE)
        // getAllHeaderLines forces IMAP to FETCH BODY[HEADER] — pulls every header line on the
        // message, not just the envelope subset that allHeaders/getHeader return. Gmail's
        // filter forwards stash the per-user alias suffix in X-Forwarded-To which is NOT in
        // the envelope subset (Delivered-To gets normalised to the base address).
        val mime = msg as? jakarta.mail.internet.MimeMessage ?: return null
        val headerBlob = mime.allHeaderLines.toList().joinToString("\n")
        val decoded = runCatching { MimeUtility.decodeText(headerBlob) }.getOrDefault(headerBlob)
        return pattern.find(decoded)?.groupValues?.get(1)
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
        private val GMAIL_APPROVE_URL = Regex("""https://(?:mail-settings|mail)\.google\.com/mail/vf-[\w%-]+""")
        private val FORWARDER_IN_BODY = Regex(
            """(?:с адреса|from\s+address|from)\s+(\S+@\S+\.\S+)""",
            RegexOption.IGNORE_CASE,
        )
        private val RETURN_PATH_FORWARDER = Regex("""<([\w.]+)\+caf_=[^>]+>""", RegexOption.IGNORE_CASE)
    }
}

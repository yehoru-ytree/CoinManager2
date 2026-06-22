package MailAggregator.MailAggregator.privatbank.api

import MailAggregator.MailAggregator.bank.BankAccount
import MailAggregator.MailAggregator.bank.BankApi
import MailAggregator.MailAggregator.bank.BankType
import MailAggregator.MailAggregator.bank.Transaction
import MailAggregator.MailAggregator.common.config.Config.Companion.TIME_ZONE
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * PrivatBank statements via the ACP API (https://acp.privatbank.ua/api).
 *
 * Auth: two request headers — `id` (Privat24-business client id, from [BankAccount.clientId]) and
 * `token` (from [BankAccount.token]). The IBAN passed as the `acc` query parameter lives in
 * [BankAccount.accountId].
 */
@Component
class PrivatbankApi : BankApi {
    override val bankType: BankType = BankType.PRIVATBANK

    companion object {
        private const val BASE_URL = "https://acp.privatbank.ua"
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")
    }

    private val client: RestClient = RestClient.builder()
        .baseUrl(BASE_URL)
        .build()

    override fun getStatements(
        account: BankAccount,
        householdId: UUID,
        from: Instant,
        to: Instant,
    ): List<Transaction> {
        val clientId = requireNotNull(account.clientId) { "PrivatBank account ${account.id} has no clientId" }
        val startDate = from.atZone(TIME_ZONE).toLocalDate().format(DATE_FORMAT)
        val endDate = to.atZone(TIME_ZONE).toLocalDate().format(DATE_FORMAT)

        val collected = mutableListOf<Transaction>()
        var followId: String? = null
        do {
            val page = try {
                fetchPage(account.token, clientId, account.accountId, startDate, endDate, followId)
            } catch (e: RestClientResponseException) {
                when (e.statusCode) {
                    HttpStatus.TOO_MANY_REQUESTS, HttpStatus.NO_CONTENT -> break
                    else -> throw e
                }
            } ?: break

            page.transactions.forEach { collected += PrivatStatementMapper.fromApi(it, householdId) }
            followId = page.nextPageId?.takeIf { page.existNextPage }
        } while (followId != null)

        return collected
    }

    private fun fetchPage(
        token: String,
        id: String,
        iban: String,
        startDate: String,
        endDate: String,
        followId: String?,
    ): PrivatApiResponse? = client.get()
        .uri { b ->
            b.path("/api/statements/transactions")
                .queryParam("acc", iban)
                .queryParam("startDate", startDate)
                .queryParam("endDate", endDate)
                .apply { followId?.let { queryParam("followId", it) } }
                .build()
        }
        .header("id", id)
        .header("token", token)
        .retrieve()
        .body(PrivatApiResponse::class.java)
}

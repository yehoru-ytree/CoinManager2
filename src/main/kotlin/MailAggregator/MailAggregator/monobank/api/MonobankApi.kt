package MailAggregator.MailAggregator.monobank.api

import MailAggregator.MailAggregator.bank.BankApi
import MailAggregator.MailAggregator.bank.BankType
import MailAggregator.MailAggregator.bank.Transaction
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.time.Instant
import java.util.UUID

@Component
class MonobankApi : BankApi {
    override val bankType: BankType = BankType.MONOBANK

    companion object {
        private const val BASE_URL = "https://api.monobank.ua"
        private val TX_LIST = object : ParameterizedTypeReference<List<MonoApiTransaction>>() {}
    }

    private val client: RestClient = RestClient.builder()
        .baseUrl(BASE_URL)
        .build()

    fun getClientInfo(token: String): MonoApiClientInfo =
        client.get()
            .uri("/personal/client-info")
            .header("X-Token", token)
            .retrieve()
            .body(MonoApiClientInfo::class.java)
            ?: MonoApiClientInfo()

    override fun getStatements(
        token: String,
        accountId: String,
        householdId: UUID,
        from: Instant,
        to: Instant,
    ): List<Transaction> {
        val fromSec = from.epochSecond
        val toSec = to.epochSecond

        return try {
            client.get()
                .uri("/personal/statement/{accountId}/{from}/{to}", accountId, fromSec, toSec)
                .header("X-Token", token)
                .retrieve()
                .body(TX_LIST)
                ?.map { MonoStatementMapper.fromApi(it, householdId) }
                ?: emptyList()
        } catch (e: RestClientResponseException) {
            when (e.statusCode) {
                HttpStatus.TOO_MANY_REQUESTS -> emptyList()
                HttpStatus.NO_CONTENT -> emptyList()
                else -> throw e
            }
        }
    }
}

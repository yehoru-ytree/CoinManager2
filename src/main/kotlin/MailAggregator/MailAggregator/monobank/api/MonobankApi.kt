package MailAggregator.MailAggregator.monobank.api

import MailAggregator.MailAggregator.monobank.application.MonoTransaction
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.time.Instant

@Component
class MonobankApi {

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

    fun getStatements(
        token: String,
        accountId: String,
        householdId: java.util.UUID,
        from: Instant,
        to: Instant = Instant.now(),
    ): List<MonoTransaction> {
        val fromSec = from.epochSecond
        val toSec = to.epochSecond

        return try {
            client.get()
                .uri("/personal/statement/{accountId}/{from}/{to}", accountId, fromSec, toSec)
                .header("X-Token", token)
                .retrieve()
                .body(TX_LIST)?.map {
                    MonoStatementMapper.fromApi(it, householdId)
                }
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

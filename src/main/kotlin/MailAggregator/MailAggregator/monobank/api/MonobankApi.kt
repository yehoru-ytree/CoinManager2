package MailAggregator.MailAggregator.monobank.api

import MailAggregator.MailAggregator.monobank.application.MonoTransaction
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.time.Instant

@Component
class MonobankApi(
    @Value("\${monobank.token}") private val token: String
) {

    companion object {
        private const val BASE_URL = "https://api.monobank.ua"
        private val TX_LIST = object : ParameterizedTypeReference<List<MonoApiTransaction>>() {}
    }

    private val client: RestClient = RestClient.builder()
        .baseUrl(BASE_URL)
        .defaultHeader("X-Token", token) // персональный токен
        .build()

    fun getClientInfo(): MonoApiClientInfo =
        client.get()
            .uri("/personal/client-info")
            .retrieve()
            .body(MonoApiClientInfo::class.java)
            ?: MonoApiClientInfo()

    fun getStatements(
        accountId: String,
        from: Instant,
        to: Instant = Instant.now()
    ): List<MonoTransaction> {
        val fromSec = from.epochSecond
        val toSec = to.epochSecond

        return try {
            client.get()
                .uri("/personal/statement/{accountId}/{from}/{to}", accountId, fromSec, toSec)
                .retrieve()
                .body(TX_LIST)?.map {
                    MonoStatementMapper.fromApi(it)
                }
                ?: emptyList()
        } catch (e: RestClientResponseException) {
            when (e.statusCode) {
                HttpStatus.TOO_MANY_REQUESTS -> {
                    emptyList()
                }
                HttpStatus.NO_CONTENT -> emptyList()
                else -> throw e
            }
        }
    }
}

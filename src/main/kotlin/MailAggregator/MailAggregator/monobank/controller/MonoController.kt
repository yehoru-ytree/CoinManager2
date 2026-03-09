package MailAggregator.MailAggregator.monobank.controller

import MailAggregator.MailAggregator.monobank.api.MonobankApi
import MailAggregator.MailAggregator.monobank.application.MonoTransaction
import MailAggregator.MailAggregator.monobank.repository.TransactionRepository
import jakarta.annotation.PostConstruct
import org.springframework.web.bind.annotation.*
import java.time.Instant
import org.springframework.transaction.annotation.Transactional

@RestController
@RequestMapping("/api/mono")
class MonoController(
    private val monobankApi: MonobankApi,
    private val transactionRepository: TransactionRepository,
) {

    @GetMapping("/ping")
    fun ping(): Map<String, String> = mapOf("status" to "ok")

    /**
     * Узнать клиентскую инфу и accountId'ы.
     */
    @GetMapping("/client-info")
    fun clientInfo() = monobankApi.getClientInfo()

    /**
     * Вытянуть выписку без сохранения.
     * Параметры:
     * - accountId (опционально) — если не задан, возьмём первый из client-info
     * - from/to (epoch seconds, опционально)
     * - hours (если from/to не заданы) — интервал назад, по умолчанию 24 часа
     */
    @GetMapping("/statement")
    fun statement(
        @RequestParam(required = false) accountId: String?,
        @RequestParam(required = false) from: Long?,
        @RequestParam(required = false) to: Long?,
        @RequestParam(required = false, defaultValue = "24") hours: Long
    ): List<MonoTransaction> {
        val accId = accountId ?: monobankApi.getClientInfo().accounts.reversed().firstOrNull()?.id
        ?: throw IllegalArgumentException("No accounts in client-info; specify accountId")

        val toInstant = to?.let(Instant::ofEpochSecond) ?: Instant.now()
        val fromInstant = from?.let(Instant::ofEpochSecond) ?: toInstant.minusSeconds(hours * 3600)

        return monobankApi.getStatements(accId, fromInstant, toInstant)
    }

    @PostMapping("/ingest")
    @Transactional
    fun ingest(
        @RequestParam(required = false) accountId: String?,
        @RequestParam(required = false) from: Long?,
        @RequestParam(required = false) to: Long?,
        @RequestParam(required = false, defaultValue = "24") hours: Long
    ): Map<String, Any> {
        val txs = statement(accountId, from, to, hours)
        transactionRepository.save(txs)
        return mapOf("ingested" to txs.size)
    }
}
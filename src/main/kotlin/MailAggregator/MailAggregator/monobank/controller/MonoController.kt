package MailAggregator.MailAggregator.monobank.controller

import MailAggregator.MailAggregator.household.repository.HouseholdRepository
import MailAggregator.MailAggregator.monobank.api.MonobankApi
import MailAggregator.MailAggregator.monobank.application.MonoTransaction
import MailAggregator.MailAggregator.monobank.repository.TransactionRepository
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * Dev/debug endpoints. Each call picks a Monobank account from the DB (by `accountId` query
 * param, or the first one if unspecified). Returns 404-style errors if none exists.
 */
@RestController
@RequestMapping("/api/mono")
class MonoController(
    private val monobankApi: MonobankApi,
    private val transactionRepository: TransactionRepository,
    private val householdRepository: HouseholdRepository,
) {

    @GetMapping("/ping")
    fun ping(): Map<String, String> = mapOf("status" to "ok")

    @GetMapping("/client-info")
    fun clientInfo(@RequestParam(required = false) accountId: String?): Any {
        val account = pickAccount(accountId) ?: return mapOf("error" to "No Monobank accounts in DB")
        return monobankApi.getClientInfo(account.token)
    }

    @GetMapping("/statement")
    fun statement(
        @RequestParam(required = false) accountId: String?,
        @RequestParam(required = false) from: Long?,
        @RequestParam(required = false) to: Long?,
        @RequestParam(required = false, defaultValue = "24") hours: Long,
    ): List<MonoTransaction> {
        val account = pickAccount(accountId)
            ?: throw IllegalArgumentException("No Monobank account in DB; bootstrap a household first")
        val user = householdRepository.findUserById(account.userId)
            ?: throw IllegalStateException("Orphan monobank_account ${account.id}")

        val toInstant = to?.let(Instant::ofEpochSecond) ?: Instant.now()
        val fromInstant = from?.let(Instant::ofEpochSecond) ?: toInstant.minusSeconds(hours * 3600)

        return monobankApi.getStatements(account.token, account.accountId, user.householdId, fromInstant, toInstant)
    }

    @PostMapping("/ingest")
    @Transactional
    fun ingest(
        @RequestParam(required = false) accountId: String?,
        @RequestParam(required = false) from: Long?,
        @RequestParam(required = false) to: Long?,
        @RequestParam(required = false, defaultValue = "24") hours: Long,
    ): Map<String, Any> {
        val txs = statement(accountId, from, to, hours)
        transactionRepository.save(txs)
        return mapOf("ingested" to txs.size)
    }

    private fun pickAccount(accountId: String?) =
        householdRepository.findAllMonobankAccounts()
            .firstOrNull { accountId == null || it.accountId == accountId }
}

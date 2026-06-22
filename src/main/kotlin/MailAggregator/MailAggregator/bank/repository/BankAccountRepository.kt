package MailAggregator.MailAggregator.bank.repository

import MailAggregator.MailAggregator.bank.BankAccount
import MailAggregator.MailAggregator.bank.BankType
import MailAggregator.MailAggregator.bank.repository.jpa.BankAccountJpaEntity
import MailAggregator.MailAggregator.bank.repository.jpa.BankAccountJpaRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class BankAccountRepository(
    private val jpa: BankAccountJpaRepository,
) {
    fun insert(account: BankAccount): BankAccount = jpa.save(account.toEntity()).toDomain()

    fun findAll(): List<BankAccount> = jpa.findAll().map { it.toDomain() }

    fun findAllByUser(userId: UUID): List<BankAccount> =
        jpa.findAllByUserId(userId).map { it.toDomain() }

    private fun BankAccount.toEntity() = BankAccountJpaEntity(
        id = id,
        userId = userId,
        bankType = bankType.name,
        token = token,
        accountId = accountId,
        clientId = clientId,
    )

    private fun BankAccountJpaEntity.toDomain() = BankAccount(
        id = id,
        userId = userId,
        bankType = BankType.fromString(bankType),
        token = token,
        accountId = accountId,
        clientId = clientId,
    )
}

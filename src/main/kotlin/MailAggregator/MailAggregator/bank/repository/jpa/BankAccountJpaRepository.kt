package MailAggregator.MailAggregator.bank.repository.jpa

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BankAccountJpaRepository : JpaRepository<BankAccountJpaEntity, UUID> {
    fun findAllByUserId(userId: UUID): List<BankAccountJpaEntity>

    fun findByBankTypeAndAccountId(bankType: String, accountId: String): BankAccountJpaEntity?

    fun findByBankTypeAndToken(bankType: String, token: String): BankAccountJpaEntity?
}

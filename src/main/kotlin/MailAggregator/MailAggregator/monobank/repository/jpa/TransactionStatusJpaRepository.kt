package MailAggregator.MailAggregator.monobank.repository.jpa

import MailAggregator.MailAggregator.monobank.application.TransactionStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface TransactionStatusJpaRepository : JpaRepository<TransactionStatusJpaEntity, UUID>{
    fun findAllByStatus(status: String): List<TransactionStatusJpaEntity>
}

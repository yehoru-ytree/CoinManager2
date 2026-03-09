package MailAggregator.MailAggregator.monobank.repository.jpa

import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface TransactionJpaRepository : JpaRepository<TransactionJpaEntity, String>

package MailAggregator.MailAggregator.bank.repository.jpa

import org.springframework.data.jpa.repository.JpaRepository

interface TransactionJpaRepository : JpaRepository<TransactionJpaEntity, String>

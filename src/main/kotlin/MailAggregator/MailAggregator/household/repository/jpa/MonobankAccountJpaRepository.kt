package MailAggregator.MailAggregator.household.repository.jpa

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface MonobankAccountJpaRepository : JpaRepository<MonobankAccountJpaEntity, UUID> {
    fun findAllByUserId(userId: UUID): List<MonobankAccountJpaEntity>
}

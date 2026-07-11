package MailAggregator.MailAggregator.common.repository.jpa

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CategoryJpaRepository : JpaRepository<CategoryJpaEntity, UUID> {
    fun findAllByHouseholdIdAndStatus(householdId: UUID, status: String): List<CategoryJpaEntity>
    fun findByHouseholdIdAndNameAndStatus(householdId: UUID, name: String, status: String): CategoryJpaEntity?
    fun findFirstByHouseholdIdAndIsOtherTrue(householdId: UUID): CategoryJpaEntity?
}

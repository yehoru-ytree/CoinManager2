package MailAggregator.MailAggregator.common.repository.jpa

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CategoryJpaRepository : JpaRepository<CategoryJpaEntity, UUID> {
    fun findAllByHouseholdId(householdId: UUID): List<CategoryJpaEntity>
    fun findByHouseholdIdAndName(householdId: UUID, name: String): CategoryJpaEntity?
    fun findByHouseholdIdAndSheetRow(householdId: UUID, sheetRow: Int): CategoryJpaEntity?
    fun findFirstByHouseholdIdAndIsOtherTrue(householdId: UUID): CategoryJpaEntity?
}

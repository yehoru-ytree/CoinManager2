package MailAggregator.MailAggregator.common.repository.jpa

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CategoryJpaRepository : JpaRepository<CategoryJpaEntity, UUID> {
    fun findByName(name: String): CategoryJpaEntity?
    fun findBySheetRow(sheetRow: Int): CategoryJpaEntity?
    fun findFirstByIsOtherTrue(): CategoryJpaEntity?
}

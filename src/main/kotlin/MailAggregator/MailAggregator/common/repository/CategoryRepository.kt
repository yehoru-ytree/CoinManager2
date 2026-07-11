package MailAggregator.MailAggregator.common.repository

import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.common.repository.jpa.CategoryJpaEntity
import MailAggregator.MailAggregator.common.repository.jpa.CategoryJpaRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class CategoryRepository(
    private val categoryJpaRepository: CategoryJpaRepository,
) {
    companion object {
        private val objectMapper: ObjectMapper = jacksonObjectMapper()
        private const val STATUS_ACTIVE = "ACTIVE"
    }

    /** Active categories only — the shape most callers want (pickers, add-flow uniqueness check). */
    fun findAll(householdId: UUID): List<Category> =
        categoryJpaRepository.findAllByHouseholdIdAndStatus(householdId, STATUS_ACTIVE).map(::toDomain)

    /** Includes soft-deleted rows. Used by [nextSheetRow] so we don't reuse deleted rows. */
    fun findAllIncludingDeleted(householdId: UUID): List<Category> =
        categoryJpaRepository.findAllByHouseholdId(householdId).map(::toDomain)

    /**
     * Return by id regardless of status — historical logs and stale keyboard callbacks need to
     * resolve a category even after it's been soft-deleted.
     */
    fun findById(id: UUID): Category? =
        categoryJpaRepository.findById(id).map(::toDomain).orElse(null)

    /** Active-only name lookup (the wizard's duplicate check). A soft-deleted name is free to reuse. */
    fun findByName(householdId: UUID, name: String): Category? =
        categoryJpaRepository.findByHouseholdIdAndNameAndStatus(householdId, name, STATUS_ACTIVE)?.let(::toDomain)

    /**
     * By-sheetRow lookup regardless of status — a categorisation callback (`c|txId|sheetRow`) tapped
     * on an old prompt should still resolve the category even if it was soft-deleted afterwards.
     */
    fun findBySheetRow(householdId: UUID, sheetRow: Int): Category? =
        categoryJpaRepository.findByHouseholdIdAndSheetRow(householdId, sheetRow)?.let(::toDomain)

    fun findOther(householdId: UUID): Category =
        categoryJpaRepository.findFirstByHouseholdIdAndIsOtherTrue(householdId)?.let(::toDomain)
            ?: error("No OTHER category for household $householdId; seed the household first.")

    /** Next free sheetRow — includes deleted rows so soft-deleted slots aren't reused. */
    fun nextSheetRow(householdId: UUID): Int =
        (categoryJpaRepository.findAllByHouseholdId(householdId).maxOfOrNull { it.sheetRow } ?: -1) + 1

    fun save(category: Category): Category {
        val entity = CategoryJpaEntity(
            id = category.id,
            householdId = category.householdId,
            name = category.name,
            displayName = category.displayName,
            sheetRow = category.sheetRow,
            priority = category.priority,
            keywords = objectMapper.valueToTree(category.keywords),
            isOther = category.isOther,
            isDefault = category.isDefault,
            status = category.status.name,
        )
        return toDomain(categoryJpaRepository.save(entity))
    }

    private fun toDomain(entity: CategoryJpaEntity): Category = Category(
        id = entity.id,
        householdId = entity.householdId,
        name = entity.name,
        displayName = entity.displayName,
        sheetRow = entity.sheetRow,
        priority = entity.priority,
        keywords = readKeywords(entity),
        isOther = entity.isOther,
        isDefault = entity.isDefault,
        status = Category.Status.valueOf(entity.status),
    )

    private fun readKeywords(entity: CategoryJpaEntity): List<String> =
        if (entity.keywords.isArray) {
            entity.keywords.map { it.asText() }
        } else {
            emptyList()
        }
}

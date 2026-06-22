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
    }

    fun findAll(householdId: UUID): List<Category> =
        categoryJpaRepository.findAllByHouseholdId(householdId).map(::toDomain)

    fun findById(id: UUID): Category? =
        categoryJpaRepository.findById(id).map(::toDomain).orElse(null)

    fun findByName(householdId: UUID, name: String): Category? =
        categoryJpaRepository.findByHouseholdIdAndName(householdId, name)?.let(::toDomain)

    fun findBySheetRow(householdId: UUID, sheetRow: Int): Category? =
        categoryJpaRepository.findByHouseholdIdAndSheetRow(householdId, sheetRow)?.let(::toDomain)

    fun findOther(householdId: UUID): Category =
        categoryJpaRepository.findFirstByHouseholdIdAndIsOtherTrue(householdId)?.let(::toDomain)
            ?: error("No OTHER category for household $householdId; seed the household first.")

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
    )

    private fun readKeywords(entity: CategoryJpaEntity): List<String> =
        if (entity.keywords.isArray) {
            entity.keywords.map { it.asText() }
        } else {
            emptyList()
        }
}

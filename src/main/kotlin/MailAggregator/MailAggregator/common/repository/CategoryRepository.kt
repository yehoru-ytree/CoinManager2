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

    fun findAll(): List<Category> = categoryJpaRepository.findAll().map(::toDomain)

    fun findById(id: UUID): Category? =
        categoryJpaRepository.findById(id).map(::toDomain).orElse(null)

    fun findByName(name: String): Category? =
        categoryJpaRepository.findByName(name)?.let(::toDomain)

    fun findBySheetRow(sheetRow: Int): Category? =
        categoryJpaRepository.findBySheetRow(sheetRow)?.let(::toDomain)

    fun findOther(): Category =
        categoryJpaRepository.findFirstByIsOtherTrue()?.let(::toDomain)
            ?: error("No category marked as is_other=true; check seed migration V3.")

    fun nextSheetRow(): Int =
        (categoryJpaRepository.findAll().maxOfOrNull { it.sheetRow } ?: -1) + 1

    fun insert(category: Category): Category = save(category)

    fun save(category: Category): Category {
        val entity = CategoryJpaEntity(
            id = category.id,
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

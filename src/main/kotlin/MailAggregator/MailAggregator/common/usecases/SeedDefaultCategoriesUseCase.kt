package MailAggregator.MailAggregator.common.usecases

import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.common.DefaultCategories
import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.household.Household
import java.util.UUID

class SeedDefaultCategoriesUseCase(
    private val categoryRepository: CategoryRepository,
) {
    fun seed(household: Household) {
        DefaultCategories.ALL.forEach { def ->
            categoryRepository.save(
                Category(
                    id = UUID.randomUUID(),
                    householdId = household.id,
                    name = def.name,
                    displayName = def.displayName,
                    sheetRow = def.sheetRow,
                    priority = def.priority,
                    keywords = def.keywords,
                    isOther = def.isOther,
                    isDefault = true,
                ),
            )
        }
    }
}

package MailAggregator.MailAggregator.common.usecases

import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.household.Household
import MailAggregator.MailAggregator.spreadsheet.usecases.UpdateSpendingsByDateUseCase
import MailAggregator.MailAggregator.spreadsheet.util.SheetRequester
import java.util.UUID

class AddCategoryUseCase(
    private val categoryRepository: CategoryRepository,
    private val sheetRequester: SheetRequester,
) {
    fun add(
        household: Household,
        name: String,
        displayName: String,
        priority: Int,
        keywords: List<String>,
    ): Category {
        val sheetRow = categoryRepository.nextSheetRow(household.id)
        val category = Category(
            id = UUID.randomUUID(),
            householdId = household.id,
            name = name,
            displayName = displayName,
            sheetRow = sheetRow,
            priority = priority,
            keywords = keywords,
            isOther = false,
        )
        val saved = categoryRepository.save(category)

        val templateRow = UpdateSpendingsByDateUseCase.START_ROW + sheetRow
        sheetRequester.updateTableRange(
            household.sheetId,
            "'${household.templateSheetTitle}'!A$templateRow",
            listOf(listOf(displayName)),
        )

        return saved
    }
}

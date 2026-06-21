package MailAggregator.MailAggregator.common.usecases

import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.spreadsheet.usecases.UpdateSpendingsByDateUseCase
import MailAggregator.MailAggregator.spreadsheet.util.SheetRequester
import java.util.UUID

class AddCategoryUseCase(
    private val categoryRepository: CategoryRepository,
    private val sheetRequester: SheetRequester,
    private val sheetId: String,
    private val templateSheetTitle: String,
) {
    fun add(name: String, displayName: String, priority: Int, keywords: List<String>): Category {
        val sheetRow = categoryRepository.nextSheetRow()
        val category = Category(
            id = UUID.randomUUID(),
            name = name,
            displayName = displayName,
            sheetRow = sheetRow,
            priority = priority,
            keywords = keywords,
            isOther = false,
        )
        val saved = categoryRepository.insert(category)

        val templateRow = UpdateSpendingsByDateUseCase.START_ROW + sheetRow
        sheetRequester.updateTableRange(
            sheetId,
            "'$templateSheetTitle'!A$templateRow",
            listOf(listOf(displayName)),
        )

        return saved
    }
}

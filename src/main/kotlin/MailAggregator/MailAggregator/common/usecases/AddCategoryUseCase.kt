package MailAggregator.MailAggregator.common.usecases

import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.common.Month
import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.common.repository.MonthCategoryLayoutRepository
import MailAggregator.MailAggregator.household.Household
import MailAggregator.MailAggregator.spreadsheet.usecases.UpdateSpendingsByDateUseCase
import MailAggregator.MailAggregator.spreadsheet.util.SheetRequester
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class AddCategoryUseCase(
    private val categoryRepository: CategoryRepository,
    private val monthCategoryLayoutRepository: MonthCategoryLayoutRepository,
    private val sheetRequester: SheetRequester,
    private val zoneId: ZoneId,
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
            isDefault = false,
        )
        val saved = categoryRepository.save(category)

        val templateRow = UpdateSpendingsByDateUseCase.START_ROW + sheetRow

        // Write to the template tab (source-of-truth for future month tabs).
        sheetRequester.updateTableRange(
            household.sheetId,
            "'${household.templateSheetTitle}'!A$templateRow",
            listOf(listOf(displayName)),
        )

        // Also patch the *current* month tab if it already exists, and extend its snapshot.
        // Otherwise a category added mid-month wouldn't show up in the current month's sheet
        // until the following month (which duplicates the up-to-date template on creation).
        val today = LocalDate.now(zoneId)
        val currentMonthTitle = "${Month.fromIndex(today.month.value).displayName} ${today.year}"
        if (sheetRequester.sheetExists(household.sheetId, currentMonthTitle)) {
            val offset = monthCategoryLayoutRepository.appendToSnapshot(
                householdId = household.id,
                year = today.year,
                month = today.month.value,
                categoryId = saved.id,
            )
            val currentMonthRow = UpdateSpendingsByDateUseCase.START_ROW + offset
            sheetRequester.updateTableRange(
                household.sheetId,
                "'$currentMonthTitle'!A$currentMonthRow",
                listOf(listOf(displayName)),
            )
        }

        return saved
    }
}

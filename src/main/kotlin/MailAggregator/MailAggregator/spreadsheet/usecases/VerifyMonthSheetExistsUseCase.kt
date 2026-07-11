package MailAggregator.MailAggregator.spreadsheet.usecase

import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.common.repository.MonthCategoryLayoutRepository
import MailAggregator.MailAggregator.household.Household
import MailAggregator.MailAggregator.spreadsheet.util.SheetRequester
import java.time.YearMonth

class VerifyMonthSheetExistsUseCase(
    private val sheetRequester: SheetRequester,
    private val categoryRepository: CategoryRepository,
    private val monthCategoryLayoutRepository: MonthCategoryLayoutRepository,
) {
    operator fun invoke(household: Household, targetSheetTitle: String) {
        val sheetId = household.sheetId
        val targetSheetAlreadyExists = sheetRequester.sheetExists(sheetId, targetSheetTitle)

        if (!targetSheetAlreadyExists) {
            sheetRequester.duplicateSheet(
                spreadsheetId = sheetId,
                sourceSheetTitle = household.templateSheetTitle,
                newSheetTitle = targetSheetTitle,
            )
            clearUserEditableArea(sheetId, targetSheetTitle)
            fillDateHeaderIfPossible(sheetId, targetSheetTitle)
            snapshotCategoryLayoutIfPossible(household, targetSheetTitle)
        }
    }

    /**
     * Freeze the current active-category layout for this new month tab. Subsequent
     * reads/writes to this tab (via Get/UpdateSpendingsByDateUseCase) look up offsets here,
     * so future renumbering of `Category.sheetRow` on delete cannot drift this month's data.
     * Skipped for tabs whose title doesn't parse as a Russian «Месяц YYYY» (defensive).
     */
    private fun snapshotCategoryLayoutIfPossible(household: Household, sheetTitle: String) {
        val yearMonth = parseYearMonth(sheetTitle) ?: return
        // Category.sheetRow == template offset (0-based) at this moment; the tab we just
        // duplicated from the template has category names at those exact rows.
        val activeByOffset = categoryRepository.findAll(household.id)
            .associate { it.id to it.sheetRow }
        monthCategoryLayoutRepository.replaceSnapshot(
            householdId = household.id,
            year = yearMonth.year,
            month = yearMonth.monthValue,
            layout = activeByOffset,
        )
    }

    private fun clearUserEditableArea(sheetId: String, sheetTitle: String) {
        sheetRequester.clearRange(sheetId, "'$sheetTitle'!B2:AF27")
    }

    private fun fillDateHeaderIfPossible(sheetId: String, sheetTitle: String) {
        val yearMonth = parseYearMonth(sheetTitle) ?: return

        val values = buildDateHeaderRow(yearMonth)

        sheetRequester.updateTableRange(
            spreadsheetId = sheetId,
            rangeName = "'$sheetTitle'!B1:AF1",
            rangeContent = listOf(values),
        )
    }

    private fun buildDateHeaderRow(yearMonth: YearMonth): List<Any> {
        val daysInMonth = yearMonth.lengthOfMonth()

        return (1..MAX_DAYS_COLUMNS).map { day ->
            if (day <= daysInMonth) {
                "=DATE(${yearMonth.year};${yearMonth.monthValue};$day)"
            } else {
                ""
            }
        }
    }

    private fun parseYearMonth(sheetTitle: String): YearMonth? {
        val match = SHEET_TITLE_REGEX.matchEntire(sheetTitle.trim()) ?: return null

        val monthName = match.groupValues[1]
        val year = match.groupValues[2].toInt()

        val month = MONTHS[monthName] ?: return null

        return YearMonth.of(year, month)
    }

    companion object {
        private const val MAX_DAYS_COLUMNS = 31

        private val SHEET_TITLE_REGEX = Regex("""([А-Яа-яЁё]+)\s*(\d{4})""")

        private val MONTHS = mapOf(
            "Январь" to 1,
            "Февраль" to 2,
            "Март" to 3,
            "Апрель" to 4,
            "Май" to 5,
            "Июнь" to 6,
            "Июль" to 7,
            "Август" to 8,
            "Сентябрь" to 9,
            "Октябрь" to 10,
            "Ноябрь" to 11,
            "Декабрь" to 12,
        )
    }
}

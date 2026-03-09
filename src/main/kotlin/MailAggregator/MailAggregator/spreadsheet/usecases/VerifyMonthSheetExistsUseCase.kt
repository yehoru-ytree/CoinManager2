package MailAggregator.MailAggregator.spreadsheet.usecase

import MailAggregator.MailAggregator.spreadsheet.util.SheetRequester
import java.time.YearMonth

class VerifyMonthSheetExistsUseCase(
    private val sheetRequester: SheetRequester,
    private val sheetId: String,
) {
    operator fun invoke(targetSheetTitle: String) {
        val targetSheetAlreadyExists = sheetRequester.sheetExists(sheetId, targetSheetTitle)

        if (!targetSheetAlreadyExists) {
            sheetRequester.duplicateSheet(
                spreadsheetId = sheetId,
                sourceSheetTitle = TEMPLATE_SHEET_TITLE,
                newSheetTitle = targetSheetTitle,
            )
            clearUserEditableArea(targetSheetTitle)
            fillDateHeaderIfPossible(targetSheetTitle)
        }
    }

    private fun clearUserEditableArea(sheetTitle: String) {
        sheetRequester.clearRange(sheetId, "'$sheetTitle'!B2:AF27")
    }

    private fun fillDateHeaderIfPossible(sheetTitle: String) {
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
        private const val TEMPLATE_SHEET_TITLE = "Февраль 2026"
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
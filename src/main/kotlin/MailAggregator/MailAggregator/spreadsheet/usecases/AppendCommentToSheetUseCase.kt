package MailAggregator.MailAggregator.spreadsheet.usecases

import MailAggregator.MailAggregator.common.Month
import MailAggregator.MailAggregator.spreadsheet.usecase.VerifyMonthSheetExistsUseCase
import MailAggregator.MailAggregator.spreadsheet.util.ExcelUtil
import MailAggregator.MailAggregator.spreadsheet.util.SheetRequester
import java.time.LocalDate

class AppendCommentToSheetUseCase(
    private val sheetRequester: SheetRequester,
    private val sheetId: String,
    private val verifyMonthSheetExistsUseCase: VerifyMonthSheetExistsUseCase,
) {
    companion object {
        const val COMMENT_ROW = 2
        private const val SEPARATOR = "; "
    }

    operator fun invoke(date: LocalDate, comment: String) {
        val sanitized = comment.replace(Regex("[\r\n]+"), " ").trim()
        if (sanitized.isEmpty()) return

        val month = Month.fromIndex(date.month.value)
        val sheetName = "${month.displayName} ${date.year}"
        verifyMonthSheetExistsUseCase(sheetName)

        val columnName = ExcelUtil.toColumnName(date.dayOfMonth)
        val cellRange = "$columnName$COMMENT_ROW"

        val existing = readCell(sheetName, cellRange)
        val newValue = if (existing.isBlank()) sanitized else "$existing$SEPARATOR$sanitized"

        sheetRequester.updateTableRange(
            sheetId,
            "'$sheetName'!$cellRange",
            listOf(listOf(newValue)),
        )
    }

    private fun readCell(sheetName: String, cellRange: String): String {
        val sheet = sheetRequester
            .getSpreadSheetByRange(sheetId, cellRange, sheetName)
            .sheets[0]
        return sheet.data[0].rowData
            .orEmpty()
            .firstOrNull()
            ?.getValues()
            ?.firstOrNull()
            ?.effectiveValue
            ?.stringValue
            .orEmpty()
    }
}

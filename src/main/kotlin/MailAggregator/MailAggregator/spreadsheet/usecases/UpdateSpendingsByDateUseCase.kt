package MailAggregator.MailAggregator.spreadsheet.usecases

import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.common.Month
import MailAggregator.MailAggregator.spreadsheet.usecase.VerifyMonthSheetExistsUseCase
import MailAggregator.MailAggregator.spreadsheet.util.ExcelUtil
import MailAggregator.MailAggregator.spreadsheet.util.SheetRequester
import java.time.LocalDate

class UpdateSpendingsByDateUseCase(
    private val sheetRequester: SheetRequester,
    private val sheetId: String,
    private val verifyMonthSheetExistsUseCase: VerifyMonthSheetExistsUseCase
) {
    companion object {
        const val START_ROW = 5
    }

    fun executeWithString(date: LocalDate, data: Map<String, Double>) {
        val month = Month.fromIndex(date.month.value)
        val sheetName = "${month.displayName} ${date.year}"

        verifyMonthSheetExistsUseCase(sheetName)

        val columnName = ExcelUtil.toColumnName(date.dayOfMonth)

        val rowsCount = Category.entries.size
        val endRow = START_ROW + rowsCount - 1
        val range = "'$sheetName'!$columnName$START_ROW:$columnName$endRow"

        val byIdx: Map<Int, Double> = data.asSequence()
            .filter { it.value != 0.0 }
            .map { (name, amount) -> (Category.fromDisplayName(name) ?: Category.OTHER).index to amount }
            .filter { (idx, _) -> idx in 0 until rowsCount }
            .toMap()

        val rows: List<List<Any>> =
            List(rowsCount) { idx -> listOf(byIdx[idx] ?: "") }

        sheetRequester.updateTableRange(sheetId, range, rows)
    }

    operator fun invoke(date: LocalDate, data: Map<Category, Double>) {
        val asStrings = data.mapKeys { (cat, _) -> cat.displayName }
        executeWithString(date, asStrings)
    }
}

package MailAggregator.MailAggregator.spreadsheet.usecases

import MailAggregator.MailAggregator.common.Month
import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.spreadsheet.usecase.VerifyMonthSheetExistsUseCase
import MailAggregator.MailAggregator.spreadsheet.util.ExcelUtil
import MailAggregator.MailAggregator.spreadsheet.util.SheetRequester
import java.time.LocalDate
import java.util.UUID

class UpdateSpendingsByDateUseCase(
    private val sheetRequester: SheetRequester,
    private val sheetId: String,
    private val verifyMonthSheetExistsUseCase: VerifyMonthSheetExistsUseCase,
    private val categoryRepository: CategoryRepository,
) {
    companion object {
        const val START_ROW = 5
    }

    operator fun invoke(date: LocalDate, data: Map<UUID, Double>) {
        val month = Month.fromIndex(date.month.value)
        val sheetName = "${month.displayName} ${date.year}"

        verifyMonthSheetExistsUseCase(sheetName)

        val columnName = ExcelUtil.toColumnName(date.dayOfMonth)

        val categories = categoryRepository.findAll()
        val maxSheetRow = categories.maxOf { it.sheetRow }
        val byUuid = categories.associateBy { it.id }

        val rowsCount = maxSheetRow + 1
        val endRow = START_ROW + rowsCount - 1
        val range = "'$sheetName'!$columnName$START_ROW:$columnName$endRow"

        val byOffset: Map<Int, Double> = data.asSequence()
            .filter { it.value != 0.0 }
            .mapNotNull { (uuid, amount) -> byUuid[uuid]?.sheetRow?.let { it to amount } }
            .toMap()

        val rows: List<List<Any>> =
            List(rowsCount) { idx -> listOf(byOffset[idx] ?: "") }

        sheetRequester.updateTableRange(sheetId, range, rows)
    }
}

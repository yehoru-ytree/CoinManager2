package MailAggregator.MailAggregator.spreadsheet.usecases

import MailAggregator.MailAggregator.common.Month
import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.common.repository.MonthCategoryLayoutRepository
import MailAggregator.MailAggregator.household.Household
import MailAggregator.MailAggregator.spreadsheet.usecase.VerifyMonthSheetExistsUseCase
import MailAggregator.MailAggregator.spreadsheet.util.ExcelUtil
import MailAggregator.MailAggregator.spreadsheet.util.SheetRequester
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class GetSpendingsByDateUseCase(
    val sheetRequester: SheetRequester,
    val verifyMonthSheetExistsUseCase: VerifyMonthSheetExistsUseCase,
    val categoryRepository: CategoryRepository,
    val monthCategoryLayoutRepository: MonthCategoryLayoutRepository,
) {
    operator fun invoke(household: Household, date: LocalDate): Map<UUID, Double> {
        val month = Month.fromIndex(date.month.value)
        val sheetName = "${month.displayName} ${date.year}"

        verifyMonthSheetExistsUseCase(household, sheetName)

        // Frozen (categoryId -> row offset) for this month tab.
        val layout = resolveLayout(household.id, date.year, date.month.value)
        if (layout.isEmpty()) return emptyMap()
        val maxOffset = layout.values.max()

        val startRow = UpdateSpendingsByDateUseCase.START_ROW
        val endRow = startRow + maxOffset

        val columnName = ExcelUtil.toColumnName(date.dayOfMonth)
        val sheet = sheetRequester.getSpreadSheetByRange(
            household.sheetId,
            "$columnName$startRow:$columnName$endRow",
            sheetName,
        ).sheets[0]

        val values = sheet.data[0].rowData.orEmpty().map { row ->
            val cell = row.getValues().orEmpty().getOrNull(0)
            val text = cell?.effectiveValue?.let { ev ->
                ev.stringValue
                    ?: ev.numberValue?.let { BigDecimal(it).stripTrailingZeros().toPlainString() }
                    ?: ev.boolValue?.toString()
                    ?: ""
            }.orEmpty()
            ExcelUtil.cellDAtaToDouble(text)
        }

        val categoryIdByOffset = layout.entries.associate { (id, offset) -> offset to id }
        return values.mapIndexedNotNull { idx, amount ->
            categoryIdByOffset[idx]?.let { it to amount }
        }.toMap()
    }

    private fun resolveLayout(householdId: UUID, year: Int, month: Int): Map<UUID, Int> {
        val snapshot = monthCategoryLayoutRepository.getSnapshot(householdId, year, month)
        if (snapshot.isNotEmpty()) return snapshot
        return categoryRepository.findAll(householdId).associate { it.id to it.sheetRow }
    }
}

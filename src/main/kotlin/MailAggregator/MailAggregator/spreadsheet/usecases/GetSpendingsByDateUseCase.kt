package MailAggregator.MailAggregator.spreadsheet.usecases

import MailAggregator.MailAggregator.common.Month
import MailAggregator.MailAggregator.common.repository.CategoryRepository
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
) {
    operator fun invoke(household: Household, date: LocalDate): Map<UUID, Double> {
        val month = Month.fromIndex(date.month.value)
        val sheetName = "${month.displayName} ${date.year}"

        verifyMonthSheetExistsUseCase(household, sheetName)

        val categories = categoryRepository.findAll(household.id)
        val maxSheetRow = categories.maxOf { it.sheetRow }
        val startRow = UpdateSpendingsByDateUseCase.START_ROW
        val endRow = startRow + maxSheetRow

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

        val bySheetRow = categories.associateBy { it.sheetRow }
        return values.mapIndexedNotNull { idx, amount ->
            bySheetRow[idx]?.let { it.id to amount }
        }.toMap()
    }
}

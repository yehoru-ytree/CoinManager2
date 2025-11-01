package MailAggregator.MailAggregator.spreadsheet.usecases

import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.common.Month
import MailAggregator.MailAggregator.spreadsheet.util.ExcelUtil
import MailAggregator.MailAggregator.spreadsheet.util.SheetRequester
import jakarta.annotation.PostConstruct
import java.math.BigDecimal
import java.time.LocalDate

class GetSpendingsByDateUseCase(
    val sheetRequester: SheetRequester,
    val sheetId: String,
) {
    //@PostConstruct
    fun test() {
        execute(LocalDate.now().minusDays(5)).values.sum()
    }

    fun execute(date: LocalDate): Map<Category, Double> {
        val month = date.month.value.let { Month.fromIndex(it) }
        val columnName = date.let { ExcelUtil.toColumnName(it.dayOfMonth ) }
        val sheet = sheetRequester.getSpreadSheetByRange(sheetId,"${columnName}5:${columnName}27", "${month.displayName} ${date.year}").sheets[0]
        val data = sheet.data[0].rowData.orEmpty().map { row ->
            val cell = row.getValues().orEmpty().getOrNull(0)
            val text = cell?.effectiveValue?.let { ev ->
                ev.stringValue
                    ?: ev.numberValue?.let { BigDecimal(it).stripTrailingZeros().toPlainString() }
                    ?: ev.boolValue?.toString()
                    ?: ""
            }.orEmpty()
            ExcelUtil.cellDAtaToDouble(text)
        }
        val res = data.mapIndexed { i, value -> Category.fromIndex(i) to value }.toMap()
        return res
    }
}
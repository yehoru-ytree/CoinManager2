package MailAggregator.MailAggregator.spreadsheet.usecases

import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.common.Month
import MailAggregator.MailAggregator.spreadsheet.util.ExcelUtil
import MailAggregator.MailAggregator.spreadsheet.util.SheetRequester
import jakarta.annotation.PostConstruct
import java.time.LocalDate

class UpdateSpendingsByDateUseCase(
    private val sheetRequester: SheetRequester,
    private val sheetId: String,
) {
    companion object {
        const val START_ROW = 5
    }

    @PostConstruct
    fun test() {
        // 27-е число в текущем месяце/году
        val date = LocalDate.now().withDayOfMonth(27)

        // Примерные суммы по категориям
        val sample: Map<Category, Double> = mapOf(
            Category.GROCERIES to 645.30,
            Category.FUEL to 1200.0,
            Category.CAFE_DELIVERY to 220.0,
            Category.WORK_LUNCH to 95.0,
            Category.UTILITIES to 800.0,
            Category.PHONE_INTERNET to 250.0,
            Category.SUBSCRIPTIONS to 199.0,
            Category.LEISURE to 350.0,
            Category.DONATIONS to 100.0,
            Category.OTHER to 50.0,
        )

        // Если у тебя есть перегрузка execute(date, Map<Category, Double>) — используем её:
        execute(date, sample)

        // Если перегрузки нет и принимается только Map<String, Double>, раскомментируй:
        // execute(date, sample.mapKeys { (k, _) -> k.displayName })
    }

    fun executeWithString(date: LocalDate, data: Map<String, Double>) {
        val month = Month.fromIndex(date.month.value)
        val sheetName = "${month.displayName} ${date.year}"
        val columnName = ExcelUtil.toColumnName(date.dayOfMonth)

        val rowsCount = Category.entries.size
        val endRow = START_ROW + rowsCount - 1
        val range = "'$sheetName'!$columnName$START_ROW:$columnName$endRow"

        val byIdx: Map<Int, Double> = data.asSequence()
            .map { (name, amount) -> (Category.fromDisplayName(name) ?: Category.OTHER).index to amount }
            .filter { (idx, _) -> idx in 0 until rowsCount }
            .toMap() // при дубликатах берётся последнее значение, как и раньше

        val rows: List<List<Any>> =
            List(rowsCount) { idx -> listOf(byIdx[idx] ?: "") }

        sheetRequester.updateTableRange(sheetId, range, rows)
    }

    /** Удобная перегрузка, если уже есть категории. */
    fun execute(date: LocalDate, data: Map<Category, Double>) {
        val asStrings = data.mapKeys { (cat, _) -> cat.displayName }
        executeWithString(date, asStrings)
    }
}

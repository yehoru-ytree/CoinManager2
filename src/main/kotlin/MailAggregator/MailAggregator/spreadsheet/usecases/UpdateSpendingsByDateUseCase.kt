package MailAggregator.MailAggregator.spreadsheet.usecases

import MailAggregator.MailAggregator.common.Month
import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.common.repository.MonthCategoryLayoutRepository
import MailAggregator.MailAggregator.household.Household
import MailAggregator.MailAggregator.spreadsheet.usecase.VerifyMonthSheetExistsUseCase
import MailAggregator.MailAggregator.spreadsheet.util.ExcelUtil
import MailAggregator.MailAggregator.spreadsheet.util.SheetRequester
import java.time.LocalDate
import java.util.UUID

class UpdateSpendingsByDateUseCase(
    private val sheetRequester: SheetRequester,
    private val verifyMonthSheetExistsUseCase: VerifyMonthSheetExistsUseCase,
    private val categoryRepository: CategoryRepository,
    private val monthCategoryLayoutRepository: MonthCategoryLayoutRepository,
) {
    companion object {
        const val START_ROW = 5
    }

    operator fun invoke(household: Household, date: LocalDate, data: Map<UUID, Double>) {
        val month = Month.fromIndex(date.month.value)
        val sheetName = "${month.displayName} ${date.year}"

        verifyMonthSheetExistsUseCase(household, sheetName)

        val columnName = ExcelUtil.toColumnName(date.dayOfMonth)

        // Frozen (categoryId -> row offset) for this month tab. Falls back to the current
        // template layout for legacy tabs created before V10 introduced snapshots.
        val layout = resolveLayout(household.id, date.year, date.month.value)
        val maxOffset = layout.values.maxOrNull() ?: return

        val rowsCount = maxOffset + 1
        val endRow = START_ROW + rowsCount - 1
        val range = "'$sheetName'!$columnName$START_ROW:$columnName$endRow"

        val byOffset: Map<Int, Double> = data.asSequence()
            .filter { it.value != 0.0 }
            .mapNotNull { (uuid, amount) -> layout[uuid]?.let { it to amount } }
            .toMap()

        val rows: List<List<Any>> =
            List(rowsCount) { idx -> listOf(byOffset[idx] ?: "") }

        sheetRequester.updateTableRange(household.sheetId, range, rows)
    }

    private fun resolveLayout(householdId: UUID, year: Int, month: Int): Map<UUID, Int> {
        val snapshot = monthCategoryLayoutRepository.getSnapshot(householdId, year, month)
        if (snapshot.isNotEmpty()) return snapshot
        // Legacy month tab (created before V10 or before this codebase started snapshotting).
        // No delete has ever renumbered categories in that scenario, so the current template
        // layout matches the on-disk layout.
        return categoryRepository.findAll(householdId).associate { it.id to it.sheetRow }
    }
}

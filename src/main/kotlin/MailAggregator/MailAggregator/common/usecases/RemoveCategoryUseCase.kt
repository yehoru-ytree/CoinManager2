package MailAggregator.MailAggregator.common.usecases

import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.common.Month
import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.common.repository.MonthCategoryLayoutRepository
import MailAggregator.MailAggregator.household.Household
import MailAggregator.MailAggregator.spreadsheet.usecases.UpdateSpendingsByDateUseCase
import MailAggregator.MailAggregator.spreadsheet.util.SheetRequester
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Soft-delete + compact.
 *
 * 1. DB: category status → DELETED. Historical transactions keep their category_id reference.
 * 2. DB: renumber every ACTIVE category with `sheetRow > deleted.sheetRow` down by 1, so the
 *    current-template layout stays dense (no gaps).
 * 3. Google Sheets template: rewrite the A column from row `START_ROW` with active category names
 *    in new sheetRow order; clear the trailing cell where the last active category used to live.
 * 4. Current month tab: physically shift rows (deletedOffset+1 .. maxOffset) up by 1 across the
 *    entire day-column span (A..AF) and clear the vacated last row. Update the month's snapshot
 *    to match.
 * 5. Past month tabs: **untouched**. Their frozen `MonthCategoryLayout` snapshot preserves the
 *    old sheetRow → offset mapping, so any subsequent read/write to those tabs (via
 *    Get/UpdateSpendingsByDateUseCase) resolves through the snapshot and stays aligned with the
 *    on-disk layout of that specific month.
 */
class RemoveCategoryUseCase(
    private val categoryRepository: CategoryRepository,
    private val monthCategoryLayoutRepository: MonthCategoryLayoutRepository,
    private val sheetRequester: SheetRequester,
    private val zoneId: ZoneId,
) {
    companion object {
        // Day 31 → column AF (ExcelUtil.toColumnName(31) == "AF"). The current-month compact
        // shifts columns A..AF up by one row for every affected row.
        private const val LAST_DAY_COLUMN = "AF"
    }

    fun remove(household: Household, categoryId: UUID): Result {
        val category = categoryRepository.findById(categoryId) ?: return Result.NotFound
        if (category.householdId != household.id) return Result.NotFound
        if (category.isDefault) return Result.CannotRemoveBase(category)
        if (category.isOther) return Result.CannotRemoveOther(category)
        if (category.status == Category.Status.DELETED) return Result.AlreadyRemoved(category)

        val deletedSheetRow = category.sheetRow
        val deleted = categoryRepository.save(category.copy(status = Category.Status.DELETED))

        renumberActiveAboveDeleted(household.id, deletedSheetRow)
        compactTemplate(household)
        compactCurrentMonthIfPresent(household, categoryId, deletedSheetRow)

        return Result.Removed(deleted)
    }

    private fun renumberActiveAboveDeleted(householdId: UUID, deletedSheetRow: Int) {
        // The (household_id, sheet_row) unique constraint is now partial on status='ACTIVE'
        // (V10), so decrementing from deletedSheetRow+1 upward can't collide with the DELETED
        // row we just wrote — it dropped out of the ACTIVE unique.
        val activeAbove = categoryRepository.findAll(householdId)
            .filter { it.sheetRow > deletedSheetRow }
            .sortedBy { it.sheetRow }
        activeAbove.forEach { above ->
            categoryRepository.save(above.copy(sheetRow = above.sheetRow - 1))
        }
    }

    private fun compactTemplate(household: Household) {
        val activeSorted = categoryRepository.findAll(household.id).sortedBy { it.sheetRow }
        val startRow = UpdateSpendingsByDateUseCase.START_ROW
        if (activeSorted.isNotEmpty()) {
            val endRow = startRow + activeSorted.size - 1
            sheetRequester.updateTableRange(
                household.sheetId,
                "'${household.templateSheetTitle}'!A$startRow:A$endRow",
                activeSorted.map { listOf<Any>(it.displayName) },
            )
        }
        // Clear the trailing cell where the last active category used to live.
        val trailingRow = startRow + activeSorted.size
        sheetRequester.clearRange(
            household.sheetId,
            "'${household.templateSheetTitle}'!A$trailingRow",
        )
    }

    private fun compactCurrentMonthIfPresent(
        household: Household,
        deletedCategoryId: UUID,
        deletedSheetRow: Int,
    ) {
        val today = LocalDate.now(zoneId)
        val currentMonthTitle = "${Month.fromIndex(today.month.value).displayName} ${today.year}"
        if (!sheetRequester.sheetExists(household.sheetId, currentMonthTitle)) return

        val snapshot = monthCategoryLayoutRepository.getSnapshot(
            household.id,
            today.year,
            today.month.value,
        ).ifEmpty {
            // Legacy month tab (no snapshot yet): materialize from the pre-delete layout.
            // The deleted category still had status=ACTIVE with sheetRow=deletedSheetRow when
            // this tab was written to, so its position on-disk matches deletedSheetRow.
            buildLegacyPreDeleteSnapshot(household.id, deletedCategoryId, deletedSheetRow)
        }

        val deletedOffset = snapshot[deletedCategoryId]
        // The category isn't on this month tab at all (edge case: the tab was created before
        // this category was added). Nothing to shift; nothing to update in the snapshot.
        if (deletedOffset == null) return

        val maxOffset = snapshot.values.max()
        val startRow = UpdateSpendingsByDateUseCase.START_ROW

        if (deletedOffset < maxOffset) {
            // Read rows (deletedOffset+1..maxOffset), write them back one row up.
            val srcStartRow = startRow + deletedOffset + 1
            val srcEndRow = startRow + maxOffset
            val srcRange = "'$currentMonthTitle'!A$srcStartRow:$LAST_DAY_COLUMN$srcEndRow"
            val block = sheetRequester.readBlockRaw(
                spreadsheetId = household.sheetId,
                rangeName = srcRange,
                expectedRows = maxOffset - deletedOffset,
                expectedCols = COLUMNS_A_TO_AF,
            )
            val dstStartRow = startRow + deletedOffset
            val dstEndRow = startRow + maxOffset - 1
            sheetRequester.updateTableRange(
                household.sheetId,
                "'$currentMonthTitle'!A$dstStartRow:$LAST_DAY_COLUMN$dstEndRow",
                block,
            )
        }
        // Clear the row that the last category used to occupy (now vacated by the shift, or
        // by the delete itself if the deleted category was the highest-offset one).
        val trailingSheetRow = startRow + maxOffset
        sheetRequester.clearRange(
            household.sheetId,
            "'$currentMonthTitle'!A$trailingSheetRow:$LAST_DAY_COLUMN$trailingSheetRow",
        )

        val newSnapshot = snapshot
            .filterKeys { it != deletedCategoryId }
            .mapValues { (_, offset) -> if (offset > deletedOffset) offset - 1 else offset }
        monthCategoryLayoutRepository.replaceSnapshot(
            householdId = household.id,
            year = today.year,
            month = today.month.value,
            layout = newSnapshot,
        )
    }

    /**
     * Reconstruct the current-month snapshot for a household that never had a snapshot written
     * (legacy pre-V10 month tab). At this call site the deleted category has already been
     * status=DELETED in the DB, so `findAll` doesn't return it — we add it back at its known
     * pre-delete `deletedSheetRow` to reflect what was actually on the sheet.
     */
    private fun buildLegacyPreDeleteSnapshot(
        householdId: UUID,
        deletedCategoryId: UUID,
        deletedSheetRow: Int,
    ): Map<UUID, Int> {
        val active = categoryRepository.findAll(householdId)
            // Active categories at this point have already been decremented past deletedSheetRow.
            // Undo that: offsets on-disk still reflect the pre-delete layout.
            .associate { it.id to if (it.sheetRow >= deletedSheetRow) it.sheetRow + 1 else it.sheetRow }
        return active + (deletedCategoryId to deletedSheetRow)
    }

    sealed class Result {
        data class Removed(val category: Category) : Result()
        data class CannotRemoveBase(val category: Category) : Result()
        data class CannotRemoveOther(val category: Category) : Result()
        data class AlreadyRemoved(val category: Category) : Result()
        data object NotFound : Result()
    }
}

private const val COLUMNS_A_TO_AF = 32 // A..AF inclusive

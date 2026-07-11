package MailAggregator.MailAggregator.common.repository

import MailAggregator.MailAggregator.common.repository.jpa.MonthCategoryLayoutId
import MailAggregator.MailAggregator.common.repository.jpa.MonthCategoryLayoutJpaEntity
import MailAggregator.MailAggregator.common.repository.jpa.MonthCategoryLayoutJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Snapshot of category-row positions in a specific monthly Google Sheets tab.
 *
 * Semantics: for a given `(householdId, year, month)`, each row of the sheet under the category
 * name column holds `displayName` of the category at that offset. Offset 0 is at sheet row
 * `UpdateSpendingsByDateUseCase.START_ROW`, offset 1 at row `START_ROW + 1`, etc. Once written
 * for a month, the snapshot is frozen — even if `Category.sheetRow` is later renumbered by
 * `RemoveCategoryUseCase`, past months keep their layout.
 */
@Service
class MonthCategoryLayoutRepository(
    private val jpa: MonthCategoryLayoutJpaRepository,
) {
    /**
     * The frozen `category_id -> row offset` mapping for the given month.
     * Empty map iff no snapshot exists yet (a legacy month tab from before this refactor —
     * callers should materialize by calling [replaceSnapshot] with the current active-category
     * layout as a fallback, or via a caller-side default).
     */
    fun getSnapshot(householdId: UUID, year: Int, month: Int): Map<UUID, Int> =
        jpa.findAllByIdHouseholdIdAndIdYearAndIdMonth(householdId, year, month)
            .associate { it.id.categoryId to it.rowOffset }

    /**
     * Overwrite the entire snapshot for a `(household, year, month)`. Used both for the initial
     * write (new month tab created by `VerifyMonthSheetExistsUseCase`) and for updates when
     * `RemoveCategoryUseCase` compacts the current month.
     */
    @Transactional
    fun replaceSnapshot(householdId: UUID, year: Int, month: Int, layout: Map<UUID, Int>) {
        jpa.deleteAllByHouseholdMonth(householdId, year, month)
        if (layout.isEmpty()) return
        jpa.saveAll(
            layout.map { (categoryId, offset) ->
                MonthCategoryLayoutJpaEntity(
                    id = MonthCategoryLayoutId(
                        householdId = householdId,
                        year = year,
                        month = month,
                        categoryId = categoryId,
                    ),
                    rowOffset = offset,
                )
            },
        )
    }

    /**
     * Append a single category at the next available offset for the given month.
     * Used by [AddCategoryUseCase] mid-month: category was just persisted and its cell in the
     * month tab has been written at the returned offset.
     */
    @Transactional
    fun appendToSnapshot(householdId: UUID, year: Int, month: Int, categoryId: UUID): Int {
        val existing = jpa.findAllByIdHouseholdIdAndIdYearAndIdMonth(householdId, year, month)
        val nextOffset = (existing.maxOfOrNull { it.rowOffset } ?: -1) + 1
        jpa.save(
            MonthCategoryLayoutJpaEntity(
                id = MonthCategoryLayoutId(
                    householdId = householdId,
                    year = year,
                    month = month,
                    categoryId = categoryId,
                ),
                rowOffset = nextOffset,
            ),
        )
        return nextOffset
    }
}

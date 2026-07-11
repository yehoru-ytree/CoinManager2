package MailAggregator.MailAggregator.spreadsheet.usecases

import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.common.repository.MonthCategoryLayoutRepository
import MailAggregator.MailAggregator.household.Household
import MailAggregator.MailAggregator.spreadsheet.usecase.VerifyMonthSheetExistsUseCase
import MailAggregator.MailAggregator.spreadsheet.util.SheetRequester
import io.mockk.every
import io.mockk.junit5.MockKExtension
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.LocalDate
import java.util.UUID

/**
 * These specs pin the whole point of the per-month layout snapshot: writes and reads to a given
 * month tab must follow that month's frozen offsets, not the current `Category.sheetRow`. If this
 * ever regresses, past-month writes drift the moment RemoveCategoryUseCase renumbers.
 */
@ExtendWith(MockKExtension::class)
class SpendingsByDateSnapshotTest {

    private val sheetRequester: SheetRequester = mockk(relaxed = true)
    private val verifyMonthSheetExistsUseCase: VerifyMonthSheetExistsUseCase = mockk()
    private val categoryRepository: CategoryRepository = mockk(relaxed = true)
    private val monthCategoryLayoutRepository: MonthCategoryLayoutRepository = mockk(relaxed = true)

    private val household = Household(
        id = UUID.randomUUID(),
        name = "H",
        sheetId = "sheet-abc",
        templateSheetTitle = "Template",
    )

    private val updateUseCase = UpdateSpendingsByDateUseCase(
        sheetRequester = sheetRequester,
        verifyMonthSheetExistsUseCase = verifyMonthSheetExistsUseCase,
        categoryRepository = categoryRepository,
        monthCategoryLayoutRepository = monthCategoryLayoutRepository,
    )

    @Test
    fun `write uses the past month's snapshot offset, ignoring the renumbered DB sheetRow`() {
        // Given: a past-month write (April 2026). The snapshot says category X sat at offset 10
        // at that time. Category.sheetRow in the DB now says 8 (a delete has renumbered).
        val date = LocalDate.of(2026, 4, 15)
        val categoryX = category(household.id, sheetRow = 8, name = "X")
        every { verifyMonthSheetExistsUseCase(household, "Апрель 2026") } just Runs
        every {
            monthCategoryLayoutRepository.getSnapshot(household.id, 2026, 4)
        } returns mapOf(categoryX.id to 10)

        // When
        updateUseCase(household, date, mapOf(categoryX.id to 50.0))

        // Then: writes go to row START_ROW(5) + snapshot offset(10) = 15, NOT + sheetRow(8) = 13.
        // Range is column P (day 15 → ExcelUtil.toColumnName(15)==P) rows 5..15 (max offset 10 → rowsCount 11).
        verify(exactly = 1) {
            sheetRequester.updateTableRange(
                household.sheetId,
                "'Апрель 2026'!P5:P15",
                match { rows ->
                    // The 50.0 must appear at index 10 in the row block; every other row is blank.
                    rows.size == 11 && rows[10] == listOf<Any>(50.0) &&
                        (0..9).all { i -> rows[i] == listOf<Any>("") }
                },
            )
        }
    }

    @Test
    fun `write falls back to current sheetRow layout when no snapshot exists yet (legacy tab)`() {
        // Given: no snapshot for June 2026 (legacy tab created before this refactor). Category X
        // currently has sheetRow=5.
        val date = LocalDate.of(2026, 6, 3)
        val categoryX = category(household.id, sheetRow = 5, name = "X")
        every { verifyMonthSheetExistsUseCase(household, "Июнь 2026") } just Runs
        every { monthCategoryLayoutRepository.getSnapshot(household.id, 2026, 6) } returns emptyMap()
        every { categoryRepository.findAll(household.id) } returns listOf(categoryX)

        // When
        updateUseCase(household, date, mapOf(categoryX.id to 100.0))

        // Then: falls back to Category.sheetRow. Day 3 → column D. Range D5:D10, value at index 5.
        verify(exactly = 1) {
            sheetRequester.updateTableRange(
                household.sheetId,
                "'Июнь 2026'!D5:D10",
                match { rows -> rows.size == 6 && rows[5] == listOf<Any>(100.0) },
            )
        }
    }

    private fun category(householdId: UUID, sheetRow: Int, name: String) = Category(
        id = UUID.randomUUID(),
        householdId = householdId,
        name = name,
        displayName = name,
        sheetRow = sheetRow,
        priority = 10,
        keywords = emptyList(),
        isOther = false,
        isDefault = false,
        status = Category.Status.ACTIVE,
    )
}

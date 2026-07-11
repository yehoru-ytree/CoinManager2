package MailAggregator.MailAggregator.common.usecases

import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.common.repository.MonthCategoryLayoutRepository
import MailAggregator.MailAggregator.household.Household
import MailAggregator.MailAggregator.spreadsheet.util.SheetRequester
import io.mockk.every
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.ZoneId
import java.util.UUID

@ExtendWith(MockKExtension::class)
class AddCategoryUseCaseTest {

    private val categoryRepository: CategoryRepository = mockk(relaxed = true)
    private val monthCategoryLayoutRepository: MonthCategoryLayoutRepository = mockk(relaxed = true)
    private val sheetRequester: SheetRequester = mockk(relaxed = true)
    private val zoneId: ZoneId = ZoneId.of("UTC")

    private val useCase = AddCategoryUseCase(
        categoryRepository = categoryRepository,
        monthCategoryLayoutRepository = monthCategoryLayoutRepository,
        sheetRequester = sheetRequester,
        zoneId = zoneId,
    )

    private val household = Household(
        id = UUID.randomUUID(),
        name = "H",
        sheetId = "sheet-abc",
        templateSheetTitle = "Template",
    )

    @Test
    fun `writes to template and to current month tab when current month tab exists`() {
        // Given: current-month sheet already exists (typical mid-month add case). The snapshot for
        // the current month accepts the new category at offset 3 (matches nextSheetRow — no prior
        // deletes mean sheetRow and snapshot offset align).
        every { categoryRepository.nextSheetRow(household.id) } returns 3
        every { categoryRepository.save(any()) } answers { firstArg() }
        every { sheetRequester.sheetExists(household.sheetId, any()) } returns true
        every {
            monthCategoryLayoutRepository.appendToSnapshot(household.id, any(), any(), any())
        } returns 3

        // When
        useCase.add(household, name = "COFFEE", displayName = "Кофе", priority = 50, keywords = emptyList())

        // Then: template writes at row 5+3=8, current-month writes at row 5+snapshotOffset=8.
        verify(exactly = 1) {
            sheetRequester.updateTableRange(household.sheetId, "'Template'!A8", listOf(listOf("Кофе")))
        }
        verify(exactly = 1) {
            sheetRequester.updateTableRange(
                household.sheetId,
                match { it.matches(Regex("'\\S+ \\d{4}'!A8")) },
                listOf(listOf("Кофе")),
            )
        }
        verify(exactly = 1) {
            monthCategoryLayoutRepository.appendToSnapshot(household.id, any(), any(), any())
        }
    }

    @Test
    fun `current month write uses the snapshot offset, not the DB sheetRow, so it stays aligned mid-month`() {
        // Given: nextSheetRow returns 26 (a late-added category), but the current month's snapshot
        // returns offset 25 — this simulates a household that had a delete earlier this month
        // (renumbering makes future sheetRow diverge from the persisted per-month offset). The
        // sheet write must follow the snapshot, not the DB sheetRow, or the current-month row
        // would land in the wrong position.
        every { categoryRepository.nextSheetRow(household.id) } returns 26
        every { categoryRepository.save(any()) } answers { firstArg() }
        every { sheetRequester.sheetExists(household.sheetId, any()) } returns true
        every {
            monthCategoryLayoutRepository.appendToSnapshot(household.id, any(), any(), any())
        } returns 25

        // When
        useCase.add(household, name = "LATE", displayName = "Поздняя", priority = 30, keywords = emptyList())

        // Then: template goes to A(5+26)=A31; current-month goes to A(5+25)=A30 (snapshot-driven).
        verify(exactly = 1) {
            sheetRequester.updateTableRange(household.sheetId, "'Template'!A31", listOf(listOf("Поздняя")))
        }
        verify(exactly = 1) {
            sheetRequester.updateTableRange(
                household.sheetId,
                match { it.matches(Regex("'\\S+ \\d{4}'!A30")) },
                listOf(listOf("Поздняя")),
            )
        }
    }

    @Test
    fun `skips current-month tab update when tab does not exist yet`() {
        // Given: no current-month sheet yet (e.g. first day of the month before any transaction)
        every { categoryRepository.nextSheetRow(household.id) } returns 3
        every { categoryRepository.save(any()) } answers { firstArg() }
        every { sheetRequester.sheetExists(household.sheetId, any()) } returns false

        // When
        useCase.add(household, name = "COFFEE", displayName = "Кофе", priority = 50, keywords = emptyList())

        // Then: only the template write happens; sheetExists checked but no snapshot mutation
        // and no second updateTableRange (the current-month tab will get the category when it's
        // first created — via VerifyMonthSheetExistsUseCase's initial snapshot).
        verify(exactly = 1) {
            sheetRequester.updateTableRange(household.sheetId, "'Template'!A8", listOf(listOf("Кофе")))
        }
        verify(exactly = 1) { sheetRequester.updateTableRange(any(), any(), any()) }
        verify(exactly = 0) {
            monthCategoryLayoutRepository.appendToSnapshot(any(), any(), any(), any())
        }
    }

    @Test
    fun `persists category with isDefault=false and default status ACTIVE`() {
        // Given
        every { categoryRepository.nextSheetRow(household.id) } returns 7
        every { categoryRepository.save(any()) } answers { firstArg() }
        every { sheetRequester.sheetExists(household.sheetId, any()) } returns false

        // When
        useCase.add(household, name = "GYM", displayName = "Спортзал", priority = 30, keywords = listOf("stayzozh"))

        // Then: saved category is user-added (isDefault=false) and ACTIVE by default
        verify(exactly = 1) {
            categoryRepository.save(
                match { category ->
                    category.householdId == household.id &&
                        category.name == "GYM" &&
                        category.displayName == "Спортзал" &&
                        category.sheetRow == 7 &&
                        category.priority == 30 &&
                        category.keywords == listOf("stayzozh") &&
                        !category.isOther &&
                        !category.isDefault &&
                        category.status == Category.Status.ACTIVE
                },
            )
        }
    }
}

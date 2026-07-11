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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.ZoneId
import java.util.UUID

@ExtendWith(MockKExtension::class)
class RemoveCategoryUseCaseTest {

    private val categoryRepository: CategoryRepository = mockk(relaxed = true)
    private val monthCategoryLayoutRepository: MonthCategoryLayoutRepository = mockk(relaxed = true)
    private val sheetRequester: SheetRequester = mockk(relaxed = true)
    private val zoneId: ZoneId = ZoneId.of("UTC")

    private val useCase = RemoveCategoryUseCase(
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
    fun `soft-deletes, renumbers higher active rows, compacts template, returns Removed`() {
        // Given: category to delete has sheetRow=3. Two active categories sit above it (sheetRow=4,5)
        // and should shift down by 1 after the delete. No current month tab yet.
        val toDelete = category(householdId = household.id, sheetRow = 3, name = "TO_DELETE", displayName = "Удалить")
        val above1 = category(householdId = household.id, sheetRow = 4, name = "ABOVE_1", displayName = "Верхняя 1")
        val above2 = category(householdId = household.id, sheetRow = 5, name = "ABOVE_2", displayName = "Верхняя 2")

        every { categoryRepository.findById(toDelete.id) } returns toDelete
        every { categoryRepository.save(any()) } answers { firstArg() }
        // findAll is called TWICE in the use case: once for `activeAbove` (post-soft-delete,
        // before renumber) and once for `compactTemplate` (after renumber). We return the
        // post-renumber state on both calls — either way the shape is what the compactTemplate
        // path needs (dense sheetRow across active rows).
        every { categoryRepository.findAll(household.id) } returns listOf(
            category(householdId = household.id, sheetRow = 0),
            category(householdId = household.id, sheetRow = 1),
            category(householdId = household.id, sheetRow = 2),
            above1.copy(sheetRow = 3),
            above2.copy(sheetRow = 4),
        )
        every { sheetRequester.sheetExists(household.sheetId, any()) } returns false

        // When
        val result = useCase.remove(household, toDelete.id)

        // Then: soft-delete happened
        verify(exactly = 1) {
            categoryRepository.save(
                match { it.id == toDelete.id && it.status == Category.Status.DELETED },
            )
        }
        // Template got compacted (5 active names re-written at A5..A9), trailing A10 cleared.
        verify(exactly = 1) {
            sheetRequester.updateTableRange(household.sheetId, "'Template'!A5:A9", any())
        }
        verify(exactly = 1) { sheetRequester.clearRange(household.sheetId, "'Template'!A10") }
        assertTrue(result is RemoveCategoryUseCase.Result.Removed)
    }

    @Test
    fun `compacts current month tab and updates its snapshot when the tab exists`() {
        // Given: current month tab already exists; snapshot places the deleted category at
        // offset 3 out of 5 (max offset 4). Two categories above it (offsets 4) shift down.
        val toDelete = category(householdId = household.id, sheetRow = 3, name = "TO_DELETE")
        val above = category(householdId = household.id, sheetRow = 4, name = "ABOVE")
        val other = category(householdId = household.id, sheetRow = 5, name = "OTHER_CAT")

        every { categoryRepository.findById(toDelete.id) } returns toDelete
        every { categoryRepository.save(any()) } answers { firstArg() }
        every { categoryRepository.findAll(household.id) } returns listOf(
            category(householdId = household.id, sheetRow = 0),
            category(householdId = household.id, sheetRow = 1),
            category(householdId = household.id, sheetRow = 2),
            above.copy(sheetRow = 3),
            other.copy(sheetRow = 4),
        )
        every { sheetRequester.sheetExists(household.sheetId, any()) } returns true
        // Snapshot for the current month, pre-delete: deleted at offset 3, other categories at 0..4.
        val snapshot = mapOf(
            UUID.randomUUID() to 0,
            UUID.randomUUID() to 1,
            UUID.randomUUID() to 2,
            toDelete.id to 3,
            above.id to 4,
        )
        every { monthCategoryLayoutRepository.getSnapshot(household.id, any(), any()) } returns snapshot

        // When
        useCase.remove(household, toDelete.id)

        // Then: current-month rows shifted up (row 9 read → row 8 written), row 9 cleared.
        // (START_ROW=5, offsets 3..4 → sheet rows 8..9)
        verify(exactly = 1) {
            sheetRequester.readBlockRaw(
                spreadsheetId = household.sheetId,
                rangeName = match { it.contains("!A9:AF9") },
                expectedRows = 1,
                expectedCols = 32,
            )
        }
        verify(exactly = 1) {
            sheetRequester.updateTableRange(
                household.sheetId,
                match { it.contains("!A8:AF8") },
                any(),
            )
        }
        verify(exactly = 1) {
            sheetRequester.clearRange(household.sheetId, match { it.contains("!A9:AF9") })
        }
        // Snapshot updated: deleted removed, above's offset decremented from 4 to 3.
        verify(exactly = 1) {
            monthCategoryLayoutRepository.replaceSnapshot(
                householdId = household.id,
                year = any(),
                month = any(),
                layout = match { newLayout ->
                    !newLayout.containsKey(toDelete.id) && newLayout[above.id] == 3
                },
            )
        }
    }

    @Test
    fun `deleting the highest-offset category clears just the trailing row (no shift needed)`() {
        // Given: deleted category is the last one on the month tab (max offset).
        val toDelete = category(householdId = household.id, sheetRow = 4, name = "LAST")
        every { categoryRepository.findById(toDelete.id) } returns toDelete
        every { categoryRepository.save(any()) } answers { firstArg() }
        every { categoryRepository.findAll(household.id) } returns listOf(
            category(householdId = household.id, sheetRow = 0),
            category(householdId = household.id, sheetRow = 1),
            category(householdId = household.id, sheetRow = 2),
            category(householdId = household.id, sheetRow = 3),
        )
        every { sheetRequester.sheetExists(household.sheetId, any()) } returns true
        every { monthCategoryLayoutRepository.getSnapshot(household.id, any(), any()) } returns mapOf(
            UUID.randomUUID() to 0,
            UUID.randomUUID() to 1,
            UUID.randomUUID() to 2,
            UUID.randomUUID() to 3,
            toDelete.id to 4,
        )

        // When
        useCase.remove(household, toDelete.id)

        // Then: no read/shift block; just the trailing row cleared at offset 4 → sheet row 9.
        verify(exactly = 0) { sheetRequester.readBlockRaw(any(), any(), any(), any()) }
        verify(exactly = 1) {
            sheetRequester.clearRange(household.sheetId, match { it.contains("!A9:AF9") })
        }
    }

    @Test
    fun `returns CannotRemoveBase for isDefault=true without touching state`() {
        val category = category(householdId = household.id, sheetRow = 1, isDefault = true)
        every { categoryRepository.findById(category.id) } returns category

        val result = useCase.remove(household, category.id)

        verify(exactly = 0) { categoryRepository.save(any()) }
        verify(exactly = 0) { sheetRequester.updateTableRange(any(), any(), any()) }
        assertTrue(result is RemoveCategoryUseCase.Result.CannotRemoveBase)
    }

    @Test
    fun `returns CannotRemoveOther for the OTHER catch-all category`() {
        val category = category(
            householdId = household.id,
            sheetRow = 999,
            isOther = true,
            isDefault = false, // exercising the isOther guard specifically
        )
        every { categoryRepository.findById(category.id) } returns category

        val result = useCase.remove(household, category.id)

        verify(exactly = 0) { categoryRepository.save(any()) }
        verify(exactly = 0) { sheetRequester.updateTableRange(any(), any(), any()) }
        assertTrue(result is RemoveCategoryUseCase.Result.CannotRemoveOther)
    }

    @Test
    fun `returns AlreadyRemoved when status is DELETED, no double-mutation`() {
        val category = category(
            householdId = household.id,
            sheetRow = 4,
            status = Category.Status.DELETED,
        )
        every { categoryRepository.findById(category.id) } returns category

        val result = useCase.remove(household, category.id)

        verify(exactly = 0) { categoryRepository.save(any()) }
        verify(exactly = 0) { sheetRequester.updateTableRange(any(), any(), any()) }
        assertTrue(result is RemoveCategoryUseCase.Result.AlreadyRemoved)
    }

    @Test
    fun `returns NotFound when repository has no such id`() {
        val missingId = UUID.randomUUID()
        every { categoryRepository.findById(missingId) } returns null

        val result = useCase.remove(household, missingId)

        assertEquals(RemoveCategoryUseCase.Result.NotFound, result)
    }

    @Test
    fun `returns NotFound when category belongs to a different household (tenant isolation)`() {
        val category = category(householdId = UUID.randomUUID(), sheetRow = 4)
        every { categoryRepository.findById(category.id) } returns category

        val result = useCase.remove(household, category.id)

        verify(exactly = 0) { categoryRepository.save(any()) }
        verify(exactly = 0) { sheetRequester.updateTableRange(any(), any(), any()) }
        assertEquals(RemoveCategoryUseCase.Result.NotFound, result)
    }

    private fun category(
        householdId: UUID,
        sheetRow: Int,
        name: String = "USERCAT_$sheetRow",
        displayName: String = "Кат $sheetRow",
        isDefault: Boolean = false,
        isOther: Boolean = false,
        status: Category.Status = Category.Status.ACTIVE,
    ) = Category(
        id = UUID.randomUUID(),
        householdId = householdId,
        name = name,
        displayName = displayName,
        sheetRow = sheetRow,
        priority = 20,
        keywords = emptyList(),
        isOther = isOther,
        isDefault = isDefault,
        status = status,
    )
}

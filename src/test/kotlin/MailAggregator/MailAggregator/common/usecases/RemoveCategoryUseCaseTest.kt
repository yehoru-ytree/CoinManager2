package MailAggregator.MailAggregator.common.usecases

import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.household.Household
import MailAggregator.MailAggregator.spreadsheet.util.SheetRequester
import io.mockk.every
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

@ExtendWith(MockKExtension::class)
class RemoveCategoryUseCaseTest {

    private val categoryRepository: CategoryRepository = mockk(relaxed = true)
    private val sheetRequester: SheetRequester = mockk(relaxed = true)

    private val useCase = RemoveCategoryUseCase(
        categoryRepository = categoryRepository,
        sheetRequester = sheetRequester,
    )

    private val household = Household(
        id = UUID.randomUUID(),
        name = "H",
        sheetId = "sheet-abc",
        templateSheetTitle = "Template",
    )

    @Test
    fun `soft-deletes category, clears template row, returns Removed result`() {
        // Given: a user-added ACTIVE category
        val category = category(id = UUID.randomUUID(), householdId = household.id, sheetRow = 4)
        every { categoryRepository.findById(category.id) } returns category
        every { categoryRepository.save(any()) } answers { firstArg() }

        // When
        val result = useCase.remove(household, category.id)

        // Then: status flipped to DELETED, template row cleared, correct sheet cell (START_ROW 5 + 4 = 9)
        verifyAll {
            categoryRepository.findById(category.id)
            categoryRepository.save(
                match { it.id == category.id && it.status == Category.Status.DELETED && it.isDefault == false },
            )
            sheetRequester.clearRange(household.sheetId, "'Template'!A9")
        }
        assertTrue(result is RemoveCategoryUseCase.Result.Removed)
    }

    @Test
    fun `returns CannotRemoveBase for isDefault=true without touching state`() {
        // Given: a base (seeded) category — isDefault=true
        val category = category(
            id = UUID.randomUUID(),
            householdId = household.id,
            sheetRow = 1,
            isDefault = true,
        )
        every { categoryRepository.findById(category.id) } returns category

        // When
        val result = useCase.remove(household, category.id)

        // Then: no save, no sheet mutation
        verify(exactly = 0) { categoryRepository.save(any()) }
        verify(exactly = 0) { sheetRequester.clearRange(any(), any()) }
        assertTrue(result is RemoveCategoryUseCase.Result.CannotRemoveBase)
        assertEquals(category.id, (result as RemoveCategoryUseCase.Result.CannotRemoveBase).category.id)
    }

    @Test
    fun `returns CannotRemoveOther for the OTHER catch-all category`() {
        // Given: the OTHER catch-all (isOther=true, isDefault also usually true — either would guard, but OTHER is checked separately)
        val category = category(
            id = UUID.randomUUID(),
            householdId = household.id,
            sheetRow = 999,
            isOther = true,
            isDefault = false, // exercising the isOther guard specifically
        )
        every { categoryRepository.findById(category.id) } returns category

        // When
        val result = useCase.remove(household, category.id)

        // Then
        verify(exactly = 0) { categoryRepository.save(any()) }
        verify(exactly = 0) { sheetRequester.clearRange(any(), any()) }
        assertTrue(result is RemoveCategoryUseCase.Result.CannotRemoveOther)
    }

    @Test
    fun `returns AlreadyRemoved when status is DELETED, no double-mutation`() {
        // Given: an already soft-deleted category
        val category = category(
            id = UUID.randomUUID(),
            householdId = household.id,
            sheetRow = 4,
            status = Category.Status.DELETED,
        )
        every { categoryRepository.findById(category.id) } returns category

        // When
        val result = useCase.remove(household, category.id)

        // Then
        verify(exactly = 0) { categoryRepository.save(any()) }
        verify(exactly = 0) { sheetRequester.clearRange(any(), any()) }
        assertTrue(result is RemoveCategoryUseCase.Result.AlreadyRemoved)
    }

    @Test
    fun `returns NotFound when repository has no such id`() {
        // Given
        val missingId = UUID.randomUUID()
        every { categoryRepository.findById(missingId) } returns null

        // When
        val result = useCase.remove(household, missingId)

        // Then
        assertEquals(RemoveCategoryUseCase.Result.NotFound, result)
    }

    @Test
    fun `returns NotFound when category belongs to a different household (tenant isolation)`() {
        // Given: category's householdId does NOT match the caller's household — protects against
        // spoofed callback data across households.
        val otherHousehold = UUID.randomUUID()
        val category = category(id = UUID.randomUUID(), householdId = otherHousehold, sheetRow = 4)
        every { categoryRepository.findById(category.id) } returns category

        // When
        val result = useCase.remove(household, category.id)

        // Then: treated as NotFound; nothing mutated
        verify(exactly = 0) { categoryRepository.save(any()) }
        verify(exactly = 0) { sheetRequester.clearRange(any(), any()) }
        assertEquals(RemoveCategoryUseCase.Result.NotFound, result)
    }

    private fun category(
        id: UUID,
        householdId: UUID,
        sheetRow: Int,
        isDefault: Boolean = false,
        isOther: Boolean = false,
        status: Category.Status = Category.Status.ACTIVE,
    ) = Category(
        id = id,
        householdId = householdId,
        name = "USERCAT",
        displayName = "Пользовательская",
        sheetRow = sheetRow,
        priority = 20,
        keywords = emptyList(),
        isOther = isOther,
        isDefault = isDefault,
        status = status,
    )
}

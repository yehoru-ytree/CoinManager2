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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.ZoneId
import java.util.UUID

@ExtendWith(MockKExtension::class)
class AddCategoryUseCaseTest {

    private val categoryRepository: CategoryRepository = mockk(relaxed = true)
    private val sheetRequester: SheetRequester = mockk(relaxed = true)
    private val zoneId: ZoneId = ZoneId.of("UTC")

    private val useCase = AddCategoryUseCase(
        categoryRepository = categoryRepository,
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
        // Given: current-month sheet already exists (typical mid-month add case)
        every { categoryRepository.nextSheetRow(household.id) } returns 3
        every { categoryRepository.save(any()) } answers { firstArg() }
        every { sheetRequester.sheetExists(household.sheetId, any()) } returns true

        // When
        useCase.add(household, name = "COFFEE", displayName = "Кофе", priority = 50, keywords = emptyList())

        // Then: START_ROW (5) + sheetRow (3) = 8; write to template AND current-month tab
        verifyAll {
            categoryRepository.nextSheetRow(household.id)
            categoryRepository.save(any())
            sheetRequester.updateTableRange(household.sheetId, "'Template'!A8", listOf(listOf("Кофе")))
            sheetRequester.sheetExists(household.sheetId, match { it.matches(Regex("\\S+ \\d{4}")) })
            sheetRequester.updateTableRange(household.sheetId, match { it.matches(Regex("'\\S+ \\d{4}'!A8")) }, listOf(listOf("Кофе")))
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

        // Then: only the template write happens; sheetExists checked but no second updateTableRange
        verify(exactly = 1) {
            sheetRequester.updateTableRange(household.sheetId, "'Template'!A8", listOf(listOf("Кофе")))
        }
        verify(exactly = 1) { sheetRequester.updateTableRange(any(), any(), any()) }
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

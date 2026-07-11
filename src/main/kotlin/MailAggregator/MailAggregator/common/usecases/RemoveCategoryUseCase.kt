package MailAggregator.MailAggregator.common.usecases

import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.household.Household
import MailAggregator.MailAggregator.spreadsheet.usecases.UpdateSpendingsByDateUseCase
import MailAggregator.MailAggregator.spreadsheet.util.SheetRequester
import java.util.UUID

/**
 * Soft-deletes a user-added category. Base (seeded) categories and the OTHER catch-all cannot
 * be removed — those cases return without mutating state so the caller can surface the reason.
 *
 * Effect:
 *  - DB: sets [Category.Status.DELETED]. Historical transactions still resolve their category_id.
 *  - Template tab: clears the `displayName` cell so future month tabs (which duplicate the template
 *    on creation) don't include the deleted row.
 *  - Existing monthly tabs: untouched. Past-month spending stays intact — clicking a stale category
 *    button on an old prompt still resolves the category by id.
 */
class RemoveCategoryUseCase(
    private val categoryRepository: CategoryRepository,
    private val sheetRequester: SheetRequester,
) {
    fun remove(household: Household, categoryId: UUID): Result {
        val category = categoryRepository.findById(categoryId)
            ?: return Result.NotFound
        if (category.householdId != household.id) return Result.NotFound
        if (category.isDefault) return Result.CannotRemoveBase(category)
        if (category.isOther) return Result.CannotRemoveOther(category)
        if (category.status == Category.Status.DELETED) return Result.AlreadyRemoved(category)

        // Soft-delete in DB — historical transactions keep the reference.
        val deleted = categoryRepository.save(category.copy(status = Category.Status.DELETED))

        // Clear the template row so newly-created month tabs (which duplicate the template) don't
        // inherit this category. Existing month tabs untouched — historical data survives.
        val templateRow = UpdateSpendingsByDateUseCase.START_ROW + category.sheetRow
        sheetRequester.clearRange(
            household.sheetId,
            "'${household.templateSheetTitle}'!A$templateRow",
        )

        return Result.Removed(deleted)
    }

    sealed class Result {
        data class Removed(val category: Category) : Result()
        data class CannotRemoveBase(val category: Category) : Result()
        data class CannotRemoveOther(val category: Category) : Result()
        data class AlreadyRemoved(val category: Category) : Result()
        data object NotFound : Result()
    }
}

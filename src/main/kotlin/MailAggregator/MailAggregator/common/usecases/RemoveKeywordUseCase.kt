package MailAggregator.MailAggregator.common.usecases

import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.common.repository.CategoryRepository
import java.util.UUID

/**
 * Drops one keyword from a category's list — the inverse of [SaveKeywordUseCase].
 *
 * Used by the recategorisation flow: when a transaction was auto-filed into the wrong category,
 * subtracting the amount from the sheet isn't enough, because the keyword that caused the match
 * would catch the next identical transaction too. Removing it is what actually fixes the rule.
 */
class RemoveKeywordUseCase(
    private val categoryRepository: CategoryRepository,
) {
    /** [keyword] is matched against the stored (already `Regex.escape`d) form, case-insensitively. */
    operator fun invoke(categoryId: UUID, keyword: String): Result {
        val category = categoryRepository.findById(categoryId) ?: return Result.CategoryNotFound

        val remaining = category.keywords.filterNot { it.equals(keyword, ignoreCase = true) }
        if (remaining.size == category.keywords.size) return Result.NotPresent(category, keyword)

        categoryRepository.save(category.copy(keywords = remaining))
        return Result.Removed(category, keyword)
    }

    sealed class Result {
        data class Removed(val category: Category, val keyword: String) : Result()
        data class NotPresent(val category: Category, val keyword: String) : Result()
        data object CategoryNotFound : Result()
    }
}

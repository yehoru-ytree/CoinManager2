package MailAggregator.MailAggregator.common.usecases

import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.common.repository.CategoryRepository
import java.util.UUID

class SaveKeywordUseCase(
    private val categoryRepository: CategoryRepository,
) {
    /**
     * Adds [rawKeyword] to [categoryId]'s keyword list. The raw text is `Regex.escape`d before
     * storing so that special characters in a transaction description don't get interpreted as
     * regex metacharacters by [CategorizeExpenseUseCase].
     *
     * Returns [Result.Saved] on success, [Result.AlreadyPresent] if the same (escaped) keyword
     * already exists in this category, or [Result.CategoryNotFound] if the category id is unknown.
     */
    operator fun invoke(categoryId: UUID, rawKeyword: String): Result {
        val trimmed = rawKeyword.trim()
        if (trimmed.isEmpty()) return Result.EmptyKeyword

        val category = categoryRepository.findById(categoryId) ?: return Result.CategoryNotFound

        val escaped = Regex.escape(trimmed)
        if (category.keywords.any { it.equals(escaped, ignoreCase = true) }) {
            return Result.AlreadyPresent(category, escaped)
        }

        val updated = category.copy(keywords = category.keywords + escaped)
        categoryRepository.save(updated)
        return Result.Saved(category, escaped)
    }

    sealed class Result {
        data class Saved(val category: Category, val keyword: String) : Result()
        data class AlreadyPresent(val category: Category, val keyword: String) : Result()
        data object CategoryNotFound : Result()
        data object EmptyKeyword : Result()
    }
}

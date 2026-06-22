package MailAggregator.MailAggregator.common.usecases

import MailAggregator.MailAggregator.bank.Transaction
import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.common.repository.CategoryRepository
import java.util.UUID
import kotlin.math.abs

class CategorizeExpenseUseCase(
    private val categoryRepository: CategoryRepository,
) {
    private companion object {
        const val UAH_MINOR: Long = 100L
        const val BRSM_FUEL_THRESHOLD_MINOR: Long = 300L * UAH_MINOR

        // Special rules need code-side closures (amount predicates, fixed literals).
        // Each entry references a Category by its stable `name` — must exist in DB.
        private val SPECIAL_RULES: List<SpecialRule> = listOf(
            SpecialRule(
                categoryName = "CAFE_DELIVERY",
                priority = 101,
                rawPatterns = listOf("БРСМ-Нафта", "\\bbrsm\\b"),
                amountPredicate = { amountMinor -> amountMinor <= BRSM_FUEL_THRESHOLD_MINOR },
            ),
            SpecialRule(
                categoryName = "UNIVERSITY",
                priority = 101,
                rawPatterns = listOf(literal("516875****2613")),
            ),
        )

        private fun literal(s: String): String = Regex.escape(s)
    }

    private data class SpecialRule(
        val categoryName: String,
        val priority: Int,
        val rawPatterns: List<String>,
        val amountPredicate: (Long) -> Boolean = { true },
    )

    private data class Rule(
        val category: Category,
        val priority: Int,
        val patterns: List<Regex>,
        val amountPredicate: (Long) -> Boolean = { true },
    )

    operator fun invoke(householdId: UUID, txs: List<Transaction>): Map<String, UUID> {
        if (txs.isEmpty()) return emptyMap()

        val categories = categoryRepository.findAll(householdId)
        val byName = categories.associateBy { it.name }
        val other = categories.first { it.isOther }

        val specialRules: List<Rule> = SPECIAL_RULES.mapNotNull { sr ->
            val cat = byName[sr.categoryName] ?: return@mapNotNull null
            Rule(
                category = cat,
                priority = sr.priority,
                patterns = sr.rawPatterns.map { Regex(it, RegexOption.IGNORE_CASE) },
                amountPredicate = sr.amountPredicate,
            )
        }

        val genericRules: List<Rule> = categories
            .filter { it.keywords.isNotEmpty() }
            .map {
                Rule(
                    category = it,
                    priority = it.priority,
                    patterns = it.keywords.map { kw -> Regex(kw, RegexOption.IGNORE_CASE) },
                )
            }

        val rules: List<Rule> = (specialRules + genericRules).sortedByDescending { it.priority }

        return txs.associate { tx -> tx.id to categorize(tx, rules, other).id }
    }

    private fun categorize(tx: Transaction, rules: List<Rule>, other: Category): Category {
        val text = normalize(
            buildString {
                append(tx.description)
                tx.comment?.let { append(' ').append(it) }
                tx.counterName?.let { append(' ').append(it) }
            },
        )

        val amountAbsMinor: Long = abs(tx.amount)

        val matched = rules.firstOrNull { rule ->
            rule.amountPredicate(amountAbsMinor) &&
                rule.patterns.any { it.containsMatchIn(text) }
        }

        return matched?.category ?: other
    }

    private fun normalize(raw: String): String =
        raw
            .lowercase()
            .replace('ё', 'е')
            .replace(Regex("\\s+"), " ")
            .trim()
}

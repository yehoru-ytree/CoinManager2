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

    /** A keyword and its compiled form. The raw string is what's stored in `Category.keywords`. */
    private data class Pattern(val raw: String, val regex: Regex)

    private data class Rule(
        val category: Category,
        val priority: Int,
        val patterns: List<Pattern>,
        /** True for a [SPECIAL_RULES] entry — lives in code, so it can't be edited away from the bot. */
        val fromCode: Boolean,
        val amountPredicate: (Long) -> Boolean = { true },
    )

    /**
     * Why a transaction landed where it did: the category it was filed under and the keyword that
     * matched. [fromCode] marks a [SPECIAL_RULES] entry — it isn't in `Category.keywords`, so
     * removing it needs a code change rather than a bot command.
     *
     * A null explanation means no rule matched and the transaction fell through to OTHER.
     */
    data class Explanation(val category: Category, val keyword: String, val fromCode: Boolean)

    operator fun invoke(householdId: UUID, txs: List<Transaction>): Map<String, UUID> {
        if (txs.isEmpty()) return emptyMap()

        val categories = categoryRepository.findAll(householdId)
        val other = categories.first { it.isOther }
        val rules = buildRules(categories)

        return txs.associate { tx -> tx.id to (match(tx, rules)?.category ?: other).id }
    }

    /**
     * Re-run the rules over [tx] and report which one matched, without touching the sheet or any
     * status. Used by the recategorisation flow to tell the user *why* a transaction was filed
     * where it was, and to offer deleting the offending keyword.
     *
     * Note this reflects the keyword set as it stands **now**, not as it stood when the
     * transaction was originally categorised — a keyword edited since then changes the answer.
     */
    fun explain(householdId: UUID, tx: Transaction): Explanation? {
        val rules = buildRules(categoryRepository.findAll(householdId))
        return match(tx, rules)
    }

    private fun buildRules(categories: List<Category>): List<Rule> {
        val byName = categories.associateBy { it.name }

        val specialRules: List<Rule> = SPECIAL_RULES.mapNotNull { sr ->
            val cat = byName[sr.categoryName] ?: return@mapNotNull null
            Rule(
                category = cat,
                priority = sr.priority,
                patterns = sr.rawPatterns.map { compile(it) },
                fromCode = true,
                amountPredicate = sr.amountPredicate,
            )
        }

        val genericRules: List<Rule> = categories
            .filter { it.keywords.isNotEmpty() }
            .map {
                Rule(
                    category = it,
                    priority = it.priority,
                    patterns = it.keywords.map(::compile),
                    fromCode = false,
                )
            }

        return (specialRules + genericRules).sortedByDescending { it.priority }
    }

    private fun compile(raw: String): Pattern = Pattern(raw, Regex(raw, RegexOption.IGNORE_CASE))

    private fun match(tx: Transaction, rules: List<Rule>): Explanation? {
        val text = normalize(
            buildString {
                append(tx.description)
                tx.comment?.let { append(' ').append(it) }
                tx.counterName?.let { append(' ').append(it) }
            },
        )

        val amountAbsMinor: Long = abs(tx.amount)

        for (rule in rules) {
            if (!rule.amountPredicate(amountAbsMinor)) continue
            val hit = rule.patterns.firstOrNull { it.regex.containsMatchIn(text) } ?: continue
            return Explanation(category = rule.category, keyword = hit.raw, fromCode = rule.fromCode)
        }
        return null
    }

    private fun normalize(raw: String): String =
        raw
            .lowercase()
            .replace('ё', 'е')
            .replace(Regex("\\s+"), " ")
            .trim()
}

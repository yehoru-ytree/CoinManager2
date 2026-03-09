package MailAggregator.MailAggregator.spreadsheet.usecases

import MailAggregator.MailAggregator.common.Category
import java.time.LocalDate

class MergeSpendingsByDateUseCase(
    val updateSpendingsByDateUseCase: UpdateSpendingsByDateUseCase,
    val getSpendingsByDateUseCase: GetSpendingsByDateUseCase
) {
    operator fun invoke(
        date: LocalDate,
        newExpenses: Map<Category, Double>,
    ) {
        val existingSpendings = getSpendingsByDateUseCase.invoke(date)

        updateSpendingsByDateUseCase(date, mergeExpenses(existingSpendings, newExpenses))
    }

    private fun mergeExpenses(
        existingSpendings: Map<Category, Double>,
        newSpendings: Map<Category, Double>
    ): Map<Category, Double> {
        return (existingSpendings.keys + newSpendings.keys)
            .associateWith { category ->
                (existingSpendings[category] ?: 0.0) + (newSpendings[category] ?: 0.0)
            }
            .filterValues { it != 0.0 }
    }
}
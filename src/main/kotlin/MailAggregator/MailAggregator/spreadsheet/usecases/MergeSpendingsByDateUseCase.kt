package MailAggregator.MailAggregator.spreadsheet.usecases

import java.time.LocalDate
import java.util.UUID

class MergeSpendingsByDateUseCase(
    val updateSpendingsByDateUseCase: UpdateSpendingsByDateUseCase,
    val getSpendingsByDateUseCase: GetSpendingsByDateUseCase,
) {
    operator fun invoke(
        date: LocalDate,
        newExpenses: Map<UUID, Double>,
    ) {
        val existingSpendings = getSpendingsByDateUseCase.invoke(date)
        updateSpendingsByDateUseCase(date, mergeExpenses(existingSpendings, newExpenses))
    }

    private fun mergeExpenses(
        existingSpendings: Map<UUID, Double>,
        newSpendings: Map<UUID, Double>,
    ): Map<UUID, Double> =
        (existingSpendings.keys + newSpendings.keys)
            .associateWith { categoryId ->
                (existingSpendings[categoryId] ?: 0.0) + (newSpendings[categoryId] ?: 0.0)
            }
            .filterValues { it != 0.0 }
}

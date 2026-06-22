package MailAggregator.MailAggregator.household.usecase

import MailAggregator.MailAggregator.common.usecases.SeedDefaultCategoriesUseCase
import MailAggregator.MailAggregator.household.BotUser
import MailAggregator.MailAggregator.household.Household
import MailAggregator.MailAggregator.household.repository.HouseholdRepository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

open class CreateHouseholdUseCase(
    private val householdRepository: HouseholdRepository,
    private val seedDefaultCategoriesUseCase: SeedDefaultCategoriesUseCase,
) {
    @Transactional
    open fun create(
        chatId: Long,
        sheetId: String,
        templateSheetTitle: String,
    ): Result {
        if (householdRepository.findUserByChatId(chatId) != null) {
            return Result.AlreadyInHousehold
        }

        val household = householdRepository.insertHousehold(
            Household(
                id = UUID.randomUUID(),
                name = null,
                sheetId = sheetId,
                templateSheetTitle = templateSheetTitle,
            ),
        )
        val user = householdRepository.insertUser(
            BotUser(
                id = UUID.randomUUID(),
                chatId = chatId,
                name = null,
                householdId = household.id,
            ),
        )
        seedDefaultCategoriesUseCase.seed(household)
        return Result.Created(household, user)
    }

    sealed class Result {
        data class Created(val household: Household, val user: BotUser) : Result()
        data object AlreadyInHousehold : Result()
    }
}

package MailAggregator.MailAggregator.household.usecase

import MailAggregator.MailAggregator.common.usecases.SeedDefaultCategoriesUseCase
import MailAggregator.MailAggregator.household.BotUser
import MailAggregator.MailAggregator.household.Household
import MailAggregator.MailAggregator.household.repository.HouseholdRepository
import MailAggregator.MailAggregator.spreadsheet.util.SheetRequester
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

open class CreateHouseholdUseCase(
    private val householdRepository: HouseholdRepository,
    private val seedDefaultCategoriesUseCase: SeedDefaultCategoriesUseCase,
    private val sheetRequester: SheetRequester,
    private val templateSpreadsheetId: String,
    private val templateSheetTitle: String,
) {
    @Transactional
    open fun create(
        chatId: Long,
        sheetId: String,
    ): Result {
        if (householdRepository.findUserByChatId(chatId) != null) {
            return Result.AlreadyInHousehold
        }

        // Copy the template tab from the master spreadsheet into the user's destination. Done
        // before persisting the household so a Google Sheets failure (permission, network, bad
        // sheetId) aborts the whole flow — no orphan household with no template tab.
        sheetRequester.copySheetToSpreadsheet(
            sourceSpreadsheetId = templateSpreadsheetId,
            sourceSheetTitle = templateSheetTitle,
            destinationSpreadsheetId = sheetId,
        )

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

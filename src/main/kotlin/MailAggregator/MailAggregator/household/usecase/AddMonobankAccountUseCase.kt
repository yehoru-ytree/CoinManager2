package MailAggregator.MailAggregator.household.usecase

import MailAggregator.MailAggregator.household.BotUser
import MailAggregator.MailAggregator.household.MonobankAccount
import MailAggregator.MailAggregator.household.repository.HouseholdRepository
import java.util.UUID

class AddMonobankAccountUseCase(
    private val householdRepository: HouseholdRepository,
) {
    fun add(user: BotUser, token: String, accountId: String): MonobankAccount =
        householdRepository.insertMonobankAccount(
            MonobankAccount(
                id = UUID.randomUUID(),
                userId = user.id,
                token = token,
                accountId = accountId,
            ),
        )
}

package MailAggregator.MailAggregator.household.usecase

import MailAggregator.MailAggregator.household.BotUser
import MailAggregator.MailAggregator.household.Household
import MailAggregator.MailAggregator.household.repository.HouseholdRepository
import MailAggregator.MailAggregator.household.repository.InviteTokenRepository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

open class JoinHouseholdUseCase(
    private val householdRepository: HouseholdRepository,
    private val inviteTokenRepository: InviteTokenRepository,
) {
    @Transactional
    open fun join(chatId: Long, token: String): Result {
        if (householdRepository.findUserByChatId(chatId) != null) {
            return Result.AlreadyInHousehold
        }
        val householdId = inviteTokenRepository.consume(token, chatId) ?: return Result.InvalidToken
        val household = householdRepository.findHousehold(householdId) ?: return Result.InvalidToken

        val user = householdRepository.insertUser(
            BotUser(
                id = UUID.randomUUID(),
                chatId = chatId,
                name = null,
                householdId = household.id,
            ),
        )
        return Result.Joined(household, user)
    }

    sealed class Result {
        data class Joined(val household: Household, val user: BotUser) : Result()
        data object AlreadyInHousehold : Result()
        data object InvalidToken : Result()
    }
}

package MailAggregator.MailAggregator.household.repository

import MailAggregator.MailAggregator.household.BotUser
import MailAggregator.MailAggregator.household.Household
import MailAggregator.MailAggregator.household.repository.jpa.BotUserJpaEntity
import MailAggregator.MailAggregator.household.repository.jpa.BotUserJpaRepository
import MailAggregator.MailAggregator.household.repository.jpa.HouseholdJpaEntity
import MailAggregator.MailAggregator.household.repository.jpa.HouseholdJpaRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class HouseholdRepository(
    private val householdJpa: HouseholdJpaRepository,
    private val botUserJpa: BotUserJpaRepository,
) {
    fun count(): Long = householdJpa.count()

    fun findAllHouseholds(): List<Household> =
        householdJpa.findAll().map { it.toDomain() }

    fun insertHousehold(household: Household): Household =
        householdJpa.save(household.toEntity()).toDomain()

    fun insertUser(user: BotUser): BotUser =
        botUserJpa.save(user.toEntity()).toDomain()

    fun findHousehold(id: UUID): Household? =
        householdJpa.findById(id).map { it.toDomain() }.orElse(null)

    fun findUserById(id: UUID): BotUser? =
        botUserJpa.findById(id).map { it.toDomain() }.orElse(null)

    fun findUserByChatId(chatId: Long): BotUser? =
        botUserJpa.findByChatId(chatId)?.toDomain()

    fun findUsersInHousehold(householdId: UUID): List<BotUser> =
        botUserJpa.findAllByHouseholdId(householdId).map { it.toDomain() }

    private fun Household.toEntity() = HouseholdJpaEntity(
        id = id,
        name = name,
        sheetId = sheetId,
        templateSheetTitle = templateSheetTitle,
    )

    private fun BotUser.toEntity() = BotUserJpaEntity(
        id = id,
        chatId = chatId,
        name = name,
        householdId = householdId,
    )

    companion object {
        private fun HouseholdJpaEntity.toDomain() = Household(
            id = id,
            name = name,
            sheetId = sheetId,
            templateSheetTitle = templateSheetTitle,
        )

        private fun BotUserJpaEntity.toDomain() = BotUser(
            id = id,
            chatId = chatId,
            name = name,
            householdId = householdId,
        )
    }
}

package MailAggregator.MailAggregator.household.repository

import MailAggregator.MailAggregator.household.BotUser
import MailAggregator.MailAggregator.household.Household
import MailAggregator.MailAggregator.household.MonobankAccount
import MailAggregator.MailAggregator.household.repository.jpa.BotUserJpaEntity
import MailAggregator.MailAggregator.household.repository.jpa.BotUserJpaRepository
import MailAggregator.MailAggregator.household.repository.jpa.HouseholdJpaEntity
import MailAggregator.MailAggregator.household.repository.jpa.HouseholdJpaRepository
import MailAggregator.MailAggregator.household.repository.jpa.MonobankAccountJpaEntity
import MailAggregator.MailAggregator.household.repository.jpa.MonobankAccountJpaRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class HouseholdRepository(
    private val householdJpa: HouseholdJpaRepository,
    private val botUserJpa: BotUserJpaRepository,
    private val monobankAccountJpa: MonobankAccountJpaRepository,
) {
    fun count(): Long = householdJpa.count()

    fun findAllHouseholds(): List<Household> =
        householdJpa.findAll().map { it.toDomain() }

    fun insertHousehold(household: Household): Household =
        householdJpa.save(household.toEntity()).toDomain()

    fun insertUser(user: BotUser): BotUser =
        botUserJpa.save(user.toEntity()).toDomain()

    fun insertMonobankAccount(account: MonobankAccount): MonobankAccount =
        monobankAccountJpa.save(account.toEntity()).toDomain()

    fun findHousehold(id: UUID): Household? =
        householdJpa.findById(id).map { it.toDomain() }.orElse(null)

    fun findUserById(id: UUID): BotUser? =
        botUserJpa.findById(id).map { it.toDomain() }.orElse(null)

    fun findUserByChatId(chatId: Long): BotUser? =
        botUserJpa.findByChatId(chatId)?.toDomain()

    fun findUsersInHousehold(householdId: UUID): List<BotUser> =
        botUserJpa.findAllByHouseholdId(householdId).map { it.toDomain() }

    fun findAllMonobankAccounts(): List<MonobankAccount> =
        monobankAccountJpa.findAll().map { it.toDomain() }

    fun findMonobankAccountsByUser(userId: UUID): List<MonobankAccount> =
        monobankAccountJpa.findAllByUserId(userId).map { it.toDomain() }

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

    private fun MonobankAccount.toEntity() = MonobankAccountJpaEntity(
        id = id,
        userId = userId,
        token = token,
        accountId = accountId,
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

        private fun MonobankAccountJpaEntity.toDomain() = MonobankAccount(
            id = id,
            userId = userId,
            token = token,
            accountId = accountId,
        )
    }
}

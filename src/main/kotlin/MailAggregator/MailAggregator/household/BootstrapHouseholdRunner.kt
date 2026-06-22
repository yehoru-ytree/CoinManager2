package MailAggregator.MailAggregator.household

import MailAggregator.MailAggregator.household.repository.HouseholdRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Seeds the very first household, user, and Monobank account from the legacy single-tenant env
 * vars on the first boot after the multi-user schema migration. Backfills `household_id` on
 * pre-existing rows in `category`, `transaction`, and `telegram_log_message` so they belong to
 * the seeded household.
 *
 * No-op on every subsequent boot — once at least one household exists, the runner exits early.
 */
@Component
class BootstrapHouseholdRunner(
    private val householdRepository: HouseholdRepository,
    private val jdbcTemplate: JdbcTemplate,
    @Value("\${telegram.owner-chat-id}") private val ownerChatId: String,
    @Value("\${google.sheet-id}") private val sheetId: String,
    @Value("\${google.template-sheet-title}") private val templateSheetTitle: String,
    @Value("\${monobank.token}") private val monobankToken: String,
    @Value("\${monobank.account-id}") private val monobankAccountId: String,
) : ApplicationRunner {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Transactional
    override fun run(args: ApplicationArguments?) {
        if (householdRepository.count() > 0) return

        val household = householdRepository.insertHousehold(
            Household(
                id = UUID.randomUUID(),
                name = "Family",
                sheetId = sheetId,
                templateSheetTitle = templateSheetTitle,
            ),
        )

        val user = householdRepository.insertUser(
            BotUser(
                id = UUID.randomUUID(),
                chatId = ownerChatId.toLong(),
                name = null,
                householdId = household.id,
            ),
        )

        householdRepository.insertMonobankAccount(
            MonobankAccount(
                id = UUID.randomUUID(),
                userId = user.id,
                token = monobankToken,
                accountId = monobankAccountId,
            ),
        )

        // Force JPA inserts to land in the DB before the JdbcTemplate updates below — otherwise
        // the FK to `household` fails because Hibernate hasn't flushed yet.
        entityManager.flush()

        jdbcTemplate.update(
            "UPDATE bankaggregator.category SET household_id = ? WHERE household_id IS NULL",
            household.id,
        )
        jdbcTemplate.update(
            "UPDATE bankaggregator.transaction SET household_id = ? WHERE household_id IS NULL",
            household.id,
        )
        jdbcTemplate.update(
            "UPDATE bankaggregator.telegram_log_message SET household_id = ? WHERE household_id IS NULL",
            household.id,
        )

        println("BootstrapHouseholdRunner: seeded household ${household.id} (chat=$ownerChatId)")
    }
}

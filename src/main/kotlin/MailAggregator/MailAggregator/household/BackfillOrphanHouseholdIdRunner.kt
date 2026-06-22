package MailAggregator.MailAggregator.household

import MailAggregator.MailAggregator.household.repository.HouseholdRepository
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Catches rows that landed in `transaction` / `telegram_log_message` between PR1 (when the
 * `household_id` column became available but new inserts didn't populate it) and PR2 (which
 * tags new inserts with `household_id`). Such rows were inserted by the legacy single-tenant
 * code path that didn't know about households.
 *
 * Runs on every boot but only performs the backfill when there is **exactly one household** —
 * otherwise we can't know which one those orphan rows belong to and we leave them alone.
 *
 * After PR3 lands (multiple households can coexist), this runner stops doing anything and can
 * be removed.
 */
@Component
@Order(20) // After BootstrapHouseholdRunner (default order 0 or so).
class BackfillOrphanHouseholdIdRunner(
    private val householdRepository: HouseholdRepository,
    private val jdbcTemplate: JdbcTemplate,
) : ApplicationRunner {
    @Transactional
    override fun run(args: ApplicationArguments?) {
        val households = householdRepository.findAllHouseholds()
        if (households.size != 1) return

        val theOnly = households.single().id

        val tx = jdbcTemplate.update(
            "UPDATE bankaggregator.transaction SET household_id = ? WHERE household_id IS NULL",
            theOnly,
        )
        val tl = jdbcTemplate.update(
            "UPDATE bankaggregator.telegram_log_message SET household_id = ? WHERE household_id IS NULL",
            theOnly,
        )
        if (tx + tl > 0) {
            println("BackfillOrphanHouseholdIdRunner: assigned household=$theOnly to $tx transactions and $tl log messages")
        }
    }
}

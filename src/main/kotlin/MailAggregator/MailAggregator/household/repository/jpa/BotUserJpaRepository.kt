package MailAggregator.MailAggregator.household.repository.jpa

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BotUserJpaRepository : JpaRepository<BotUserJpaEntity, UUID> {
    fun findByChatId(chatId: Long): BotUserJpaEntity?
    fun findAllByHouseholdId(householdId: UUID): List<BotUserJpaEntity>
}

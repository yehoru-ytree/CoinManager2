package MailAggregator.MailAggregator.household.repository

import MailAggregator.MailAggregator.household.repository.jpa.InviteTokenJpaEntity
import MailAggregator.MailAggregator.household.repository.jpa.InviteTokenJpaRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class InviteTokenRepository(
    private val jpa: InviteTokenJpaRepository,
) {
    fun create(householdId: UUID): String {
        val token = generateToken()
        jpa.save(InviteTokenJpaEntity(token = token, householdId = householdId))
        return token
    }

    /** Returns the household_id this token grants access to, or null if the token is unknown or
     *  already used. Marks the token as consumed on success. */
    fun consume(token: String, chatId: Long): UUID? {
        val entity = jpa.findById(token).orElse(null) ?: return null
        if (entity.usedAt != null) return null
        jpa.save(entity.copy(usedAt = Instant.now(), usedByChatId = chatId))
        return entity.householdId
    }

    private fun generateToken(): String =
        UUID.randomUUID().toString().replace("-", "").take(TOKEN_LENGTH)

    companion object {
        private const val TOKEN_LENGTH = 10
    }
}

package MailAggregator.MailAggregator.telegram

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class CallbackIdsTest {

    @Test
    fun `encode is 22 chars and round-trips`() {
        repeat(100) {
            val id = UUID.randomUUID()
            val encoded = CallbackIds.encode(id)
            assertEquals(22, encoded.length)
            assertEquals(id, CallbackIds.decode(encoded))
        }
    }

    @Test
    fun `decode still accepts the canonical form sent by older keyboards`() {
        val id = UUID.randomUUID()
        assertEquals(id, CallbackIds.decode(id.toString()))
    }

    @Test
    fun `decode rejects garbage`() {
        assertNull(CallbackIds.decode("IGNORE"))
        assertNull(CallbackIds.decode(""))
        assertNull(CallbackIds.decode("not-a-uuid-not-a-uuid-not-a-uuid-xx"))
        assertNull(CallbackIds.decode("!!!!!!!!!!!!!!!!!!!!!!"))
    }
}

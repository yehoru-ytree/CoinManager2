package MailAggregator.MailAggregator.telegram

import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

/**
 * Compact UUID encoding for inline-keyboard `callback_data`.
 *
 * Telegram rejects the whole `sendMessage` (BUTTON_DATA_INVALID) when any button's
 * `callback_data` exceeds 64 bytes. A canonical UUID is 36 chars, so `c|<txId>|<uuid>` only
 * fits while the transaction id stays under ~24 chars — cash ids (`cash-<uuid>`, 41 chars) blow
 * past it. Base64url without padding packs the same 16 bytes into 22 chars.
 *
 * [decode] also accepts the canonical 36-char form so keyboards sent before this encoding
 * existed keep working.
 */
object CallbackIds {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /** Telegram's hard limit on `callback_data`, in bytes. */
    const val MAX_CALLBACK_DATA_BYTES = 64

    fun encode(id: UUID): String {
        val bytes = ByteBuffer.allocate(16)
            .putLong(id.mostSignificantBits)
            .putLong(id.leastSignificantBits)
            .array()
        return encoder.encodeToString(bytes)
    }

    fun decode(raw: String): UUID? {
        if (raw.length == 36) return runCatching { UUID.fromString(raw) }.getOrNull()
        val bytes = runCatching { decoder.decode(raw) }.getOrNull() ?: return null
        if (bytes.size != 16) return null
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }
}

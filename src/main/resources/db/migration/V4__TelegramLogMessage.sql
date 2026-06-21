CREATE TABLE IF NOT EXISTS bankaggregator.telegram_log_message (
    id             uuid PRIMARY KEY,
    chat_id        bigint NOT NULL,
    message_id     bigint NOT NULL,
    transaction_id text   NOT NULL,
    comment        text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (chat_id, message_id)
);

CREATE INDEX IF NOT EXISTS telegram_log_message_tx_idx
    ON bankaggregator.telegram_log_message (transaction_id);

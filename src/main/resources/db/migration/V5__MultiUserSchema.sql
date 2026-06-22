-- New tenant tables.
CREATE TABLE IF NOT EXISTS bankaggregator.household (
    id                   uuid PRIMARY KEY,
    name                 text,
    sheet_id             text NOT NULL,
    template_sheet_title text NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now()
);

-- "user" is a Postgres reserved word — table is named "bot_user" to avoid quoting everywhere.
CREATE TABLE IF NOT EXISTS bankaggregator.bot_user (
    id           uuid PRIMARY KEY,
    chat_id      bigint NOT NULL UNIQUE,
    name         text,
    household_id uuid NOT NULL REFERENCES bankaggregator.household (id) ON DELETE CASCADE,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS bot_user_household_idx ON bankaggregator.bot_user (household_id);

CREATE TABLE IF NOT EXISTS bankaggregator.monobank_account (
    id         uuid PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES bankaggregator.bot_user (id) ON DELETE CASCADE,
    token      text NOT NULL,
    account_id text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, account_id)
);

CREATE INDEX IF NOT EXISTS monobank_account_user_idx ON bankaggregator.monobank_account (user_id);

-- household_id on existing tables (nullable until BootstrapHouseholdRunner backfills).
-- The runner runs once on first boot and converts the single-tenant setup into a household.
ALTER TABLE bankaggregator.category
    ADD COLUMN IF NOT EXISTS household_id uuid REFERENCES bankaggregator.household (id) ON DELETE CASCADE;
CREATE INDEX IF NOT EXISTS category_household_idx ON bankaggregator.category (household_id);

ALTER TABLE bankaggregator.transaction
    ADD COLUMN IF NOT EXISTS household_id uuid REFERENCES bankaggregator.household (id) ON DELETE CASCADE;
CREATE INDEX IF NOT EXISTS transaction_household_idx ON bankaggregator.transaction (household_id);

ALTER TABLE bankaggregator.telegram_log_message
    ADD COLUMN IF NOT EXISTS household_id uuid REFERENCES bankaggregator.household (id) ON DELETE CASCADE;
CREATE INDEX IF NOT EXISTS telegram_log_message_household_idx ON bankaggregator.telegram_log_message (household_id);

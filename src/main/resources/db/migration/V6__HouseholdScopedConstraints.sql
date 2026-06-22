-- Reshape uniqueness from global to per-household. Allows multiple households to coexist with
-- their own «OTHER» category, their own sheet_row layout, and their own per-name categories.
-- household_id is left nullable at the DB level because V6 may run on a fresh boot before
-- BootstrapHouseholdRunner has created the bootstrap household; the application enforces NOT NULL
-- via JPA `@Column(nullable = false)`.

ALTER TABLE bankaggregator.category DROP CONSTRAINT IF EXISTS category_name_key;
ALTER TABLE bankaggregator.category DROP CONSTRAINT IF EXISTS category_sheet_row_key;
DROP INDEX IF EXISTS bankaggregator.category_one_other;

ALTER TABLE bankaggregator.category
    ADD CONSTRAINT category_household_name_unique UNIQUE (household_id, name);
ALTER TABLE bankaggregator.category
    ADD CONSTRAINT category_household_sheet_row_unique UNIQUE (household_id, sheet_row);

CREATE UNIQUE INDEX IF NOT EXISTS category_one_other_per_household
    ON bankaggregator.category (household_id) WHERE is_other;

-- Invite tokens (one-time codes that link a chat_id to an existing household).
CREATE TABLE IF NOT EXISTS bankaggregator.invite_token (
    token           text PRIMARY KEY,
    household_id    uuid NOT NULL REFERENCES bankaggregator.household (id) ON DELETE CASCADE,
    created_at      timestamptz NOT NULL DEFAULT now(),
    used_at         timestamptz,
    used_by_chat_id bigint
);

CREATE INDEX IF NOT EXISTS invite_token_household_idx
    ON bankaggregator.invite_token (household_id);

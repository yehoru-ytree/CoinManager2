-- Generalise the "monobank account" linkage into a bank-agnostic "bank account" with a
-- discriminator. Existing rows belong to Monobank, so default the new column accordingly.
ALTER TABLE bankaggregator.monobank_account RENAME TO bank_account;
ALTER TABLE bankaggregator.bank_account ADD COLUMN IF NOT EXISTS bank_type text NOT NULL DEFAULT 'MONOBANK';

-- Old constraint name (Postgres derives it from the original table name) — rename to match the
-- new table name for clarity. `IF EXISTS` keeps the migration idempotent on fresh installs that
-- never had the old name.
ALTER TABLE bankaggregator.bank_account
    DROP CONSTRAINT IF EXISTS monobank_account_pkey;
ALTER TABLE bankaggregator.bank_account
    DROP CONSTRAINT IF EXISTS monobank_account_user_id_account_id_key;
-- Some PG versions auto-rename PK/UNIQUE constraints with the table; re-create idempotently.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'bank_account_pkey' AND conrelid = 'bankaggregator.bank_account'::regclass
    ) THEN
        ALTER TABLE bankaggregator.bank_account ADD PRIMARY KEY (id);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'bank_account_user_account_unique' AND conrelid = 'bankaggregator.bank_account'::regclass
    ) THEN
        ALTER TABLE bankaggregator.bank_account
            ADD CONSTRAINT bank_account_user_account_unique UNIQUE (user_id, account_id);
    END IF;
END $$;

-- Rename the user-id index too if it kept the old name.
ALTER INDEX IF EXISTS bankaggregator.monobank_account_user_idx RENAME TO bank_account_user_idx;

-- Flatten the `transaction.raw` JSON blob into typed columns. Outside the monobank package no
-- one cares about Mono-specific fields (mcc, hold, balance, ...) so we drop them — the BankApi
-- contract returns only the flat shape.
ALTER TABLE bankaggregator.transaction
    ADD COLUMN IF NOT EXISTS description text,
    ADD COLUMN IF NOT EXISTS tx_time bigint,
    ADD COLUMN IF NOT EXISTS amount bigint,
    ADD COLUMN IF NOT EXISTS currency_code int,
    ADD COLUMN IF NOT EXISTS comment text,
    ADD COLUMN IF NOT EXISTS counter_name text;

UPDATE bankaggregator.transaction
SET description = raw->>'description',
    tx_time = (raw->>'time')::bigint,
    amount = (raw->>'amount')::bigint,
    currency_code = (raw->>'currencyCode')::int,
    comment = raw->>'comment',
    counter_name = raw->>'counterName'
WHERE description IS NULL;

ALTER TABLE bankaggregator.transaction DROP COLUMN IF EXISTS raw;

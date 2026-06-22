-- PrivatBank ACP API needs three credentials per linked account: the bearer token,
-- a separate `id` header (Privat24-business client id), and the IBAN. The existing
-- `token` + `account_id` columns cover the first and third; `client_id` is the new
-- piece. Monobank accounts leave it NULL.
ALTER TABLE bankaggregator.bank_account
    ADD COLUMN IF NOT EXISTS client_id text;

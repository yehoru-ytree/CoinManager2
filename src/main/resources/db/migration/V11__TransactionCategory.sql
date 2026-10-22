-- Remember which category a transaction was booked into.
--
-- Until now transaction_status recorded only EXECUTED/IGNORED — the (transaction -> category)
-- link was lost the moment MergeSpendingsByDateUseCase folded the amount into the sheet. That
-- made the write un-undoable: nothing knew which row to subtract from.
--
-- The recategorisation flow (reply «Удалить» on a transaction log) needs exactly that link, so
-- EXECUTED rows now carry the category they were merged into. Rows written before this migration
-- keep NULL — RecategorizeWizard asks the user which category to subtract from in that case.
--
-- ON DELETE SET NULL rather than CASCADE: a household's spending history must survive a category
-- row being hard-deleted. (Normal category removal is a soft delete — see V9 — so in practice the
-- referenced row stays put and the FK never fires.)
ALTER TABLE bankaggregator.transaction_status
    ADD COLUMN IF NOT EXISTS category_id uuid;

ALTER TABLE bankaggregator.transaction_status
    DROP CONSTRAINT IF EXISTS transaction_status_category_fk;

ALTER TABLE bankaggregator.transaction_status
    ADD CONSTRAINT transaction_status_category_fk
    FOREIGN KEY (category_id) REFERENCES bankaggregator.category (id) ON DELETE SET NULL;

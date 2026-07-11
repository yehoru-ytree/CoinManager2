-- Add is_default flag and status enum to the category table.
--
-- is_default:
--   True for the 23 seed categories from DefaultCategories.ALL (V3 bootstrap + every household
--   created via SeedDefaultCategoriesUseCase). Base categories cannot be removed via the bot's
--   "Удалить категорию" wizard — only user-added ones can.
--
-- status:
--   ACTIVE  (default) — shown in pickers, new transactions may reference it.
--   DELETED           — soft-deleted via the wizard. Hidden from add/save/remove pickers, but
--                       historical transactions keep their category_id reference so
--                       past-month spending stays intact.

ALTER TABLE bankaggregator.category
    ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE bankaggregator.category
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';

-- Backfill: every category whose name matches one of the DefaultCategories.ALL entries is a base
-- category (V3 seeded them for the bootstrap household; every subsequent household's seed writes
-- the same set via SeedDefaultCategoriesUseCase). Matching by name is safe because the wizard's
-- add flow rejects duplicates within a household, so a user's custom category cannot share a name
-- with an already-seeded default in the same household.
UPDATE bankaggregator.category
   SET is_default = true
 WHERE name IN (
    'FLAT', 'UTILITIES', 'GROCERIES', 'FUEL', 'CAR_REPAIR', 'TRANSIT_TAXI',
    'CAFE_DELIVERY', 'LEISURE', 'CLOTHING', 'HOUSEHOLD_GOODS', 'POKER', 'GYM',
    'HEALTH', 'RESERVATION', 'DONATIONS', 'BEAUTY', 'SAVINGS', 'UNIVERSITY',
    'WORK_LUNCH', 'CREDIT_CARD', 'PHONE_INTERNET', 'OTHER', 'SUBSCRIPTIONS'
 );

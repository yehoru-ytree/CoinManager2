-- Per-month category layout snapshot.
--
-- Each monthly Google Sheets tab («Июль 2026», etc.) has a fixed row layout the day it's created:
-- category X lives at row (5 + offset), category Y at row (5 + offset+1), and so on. That layout
-- is snapshotted here once per (household_id, year, month), and every subsequent read/write to
-- that month tab (via Get/UpdateSpendingsByDateUseCase) consults this table instead of the
-- current Category.sheet_row.
--
-- Why: RemoveCategoryUseCase renumbers Category.sheet_row on delete to keep the CURRENT
-- template + current month tab dense (no gaps). If reads/writes to past month tabs used the
-- renumbered Category.sheet_row, they'd point at the wrong on-disk row — past-month data
-- would drift. With a frozen per-month snapshot, past months are immune.

CREATE TABLE IF NOT EXISTS bankaggregator.month_category_layout (
    household_id uuid NOT NULL REFERENCES bankaggregator.household (id) ON DELETE CASCADE,
    year         int  NOT NULL,
    month        int  NOT NULL,
    category_id  uuid NOT NULL REFERENCES bankaggregator.category   (id) ON DELETE CASCADE,
    row_offset   int  NOT NULL,
    PRIMARY KEY (household_id, year, month, category_id),
    UNIQUE (household_id, year, month, row_offset)
);

CREATE INDEX IF NOT EXISTS month_category_layout_by_month
    ON bankaggregator.month_category_layout (household_id, year, month);

-- Replace the strict (household_id, sheet_row) unique constraint from V6 with a partial index
-- limited to ACTIVE categories. RemoveCategoryUseCase shifts every ACTIVE row above the
-- deleted one down by 1 to keep the template dense; if a DELETED row still occupies its
-- pre-delete sheet_row, that shift would collide. DELETED rows don't participate in the
-- current-template layout — their historical position lives in month_category_layout for
-- past months where they still appear.
ALTER TABLE bankaggregator.category
    DROP CONSTRAINT IF EXISTS category_household_sheet_row_unique;

CREATE UNIQUE INDEX IF NOT EXISTS category_household_active_sheet_row_unique
    ON bankaggregator.category (household_id, sheet_row)
    WHERE status = 'ACTIVE';

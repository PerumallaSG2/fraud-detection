-- timestamptz keeps only the instant, so the original UTC offset is stored beside it.
-- Existing rows were already normalized to UTC, so an offset of 0 is correct for them.
ALTER TABLE transactions ADD COLUMN transaction_offset_seconds INT NOT NULL DEFAULT 0;

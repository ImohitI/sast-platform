-- The contract carries ai.suggestedFix, but V1 had no column for it, so it was silently dropped.
-- A new migration, never an edit to V1: V1 has already run on existing databases.
ALTER TABLE findings ADD COLUMN ai_suggested_fix TEXT;

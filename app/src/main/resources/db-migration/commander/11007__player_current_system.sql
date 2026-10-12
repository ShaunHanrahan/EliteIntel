-- The commander's current star system, under the name it always meant.
--
-- The column was called current_primary_star, but it has always held the name of the SYSTEM the commander is in,
-- never a star's. The two differ in hundreds of systems ("78 Iota Leonis A" is the primary star of 78 Iota
-- Leonis), and reading one as the other is the confusion the location rework removed from the code.
--
-- RENAME COLUMN rewrites the table in place and keeps every value. No view, trigger or index names the column.
--
-- NOTE: no semicolon may appear inside these comments. Migrations are split on a semicolon at end of
-- line before comments are stripped, so one here would cut the file mid-comment and hand SQLite a
-- statement with no SQL in it.
ALTER TABLE player
    RENAME COLUMN current_primary_star TO current_system;

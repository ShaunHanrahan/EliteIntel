-- The desktop HUD card is placed by section of its monitor, and remembers a spot in each.
--
-- Commanders asked to move the card by voice. The monitor the card is on is cut into a 3x3 grid named
-- for the compass, and "move the overlay to the north east" sends it to that section. The card is pinned
-- to the edge of its section, so a card low on the screen grows upward instead of off the bottom.
--
-- overlaySection is the section of the last placement, by enum name (NORTH_EAST and so on). Empty means
-- a row from before sections existed, and then overlayX/overlayY still hold a window top-left corner. The
-- overlay converts that on first start, and from then on overlayX/overlayY hold the anchor point of
-- overlaySection.
--
-- overlaySectionPoints is the spot the commander last left the card in each section, as wire-name=x,y
-- pairs joined by a semicolon, e.g. ne=1720,40 followed by sw=40,1040. One TEXT column rather than sixteen
-- integer ones, because the set is read and written whole, like overlayColors. Empty means no section
-- was ever adjusted, and each section then opens at its default spot.
--
-- NOTE: no semicolon may appear inside these comments. Migrations are split on a semicolon at end of
-- line before comments are stripped, so one here would cut the file mid-comment and hand SQLite a
-- statement with no SQL in it.
ALTER TABLE game_session
    ADD COLUMN overlaySection TEXT NOT NULL DEFAULT '';

ALTER TABLE game_session
    ADD COLUMN overlaySectionPoints TEXT NOT NULL DEFAULT '';

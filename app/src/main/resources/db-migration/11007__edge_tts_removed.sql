-- Microsoft Edge Read Aloud is gone from the app, and its commanders move to Kokoro.
--
-- The Edge engine did not use a published Microsoft API. It reached the consumer Read Aloud service built
-- for the Edge browser by presenting itself as that browser, with Edge's client token, user agent and
-- extension origin and a reproduction of Edge's time-bound access token. That is not a use Microsoft
-- offers to third parties, so the engine was removed in v1.1.0026 rather than kept working against them.
--
-- Kokoro is the shipped default and needs nothing configured, so a commander who was on Edge still has a
-- voice on first start. A Cyrillic session is moved on to Supertonic by TtsProvider.forSession as usual.
-- Each ship's stored voice name is left alone. Kokoro reads a name it does not carry as its default voice,
-- the same as after any engine switch, and the ship table belongs to the commander file, which a shared
-- migration may not touch.
--
-- TtsProvider.fromStored already reads an unknown value as KOKORO. This makes the row say so too, so the
-- settings tab and the support bundle show the engine that is actually speaking.
--
-- NOTE: no semicolon may appear inside these comments. Migrations are split on a semicolon at end of
-- line before comments are stripped, so one here would cut the file mid-comment and hand SQLite a
-- statement with no SQL in it.
UPDATE game_session
SET ttsProvider = 'KOKORO'
WHERE ttsProvider = 'EDGE';

# EgyBest private preview

The separate **تشغيل EgyBest** button below TopCinema uses the existing
season, episode and server UI and the existing Watch Hub player. The provider
searches https://egytbest.live/ and resolves public server pages afresh on replay.
Only the stable WordPress post identifier is saved as a source reference.
Temporary video URLs and required HTTP headers remain in memory.

Title, production/debut year and season/episode must match. Episode numbers
come from displayed titles, never URL slugs, which were inconsistent on sampled
pages. Publication dates are not production dates. Episodes that advertise only
a later season's year are omitted unless an IMDb link confirms their identity.
Consequently some available website episodes will deliberately not be returned.
The page URL is attribution and does not assert any distribution licence.

Supported embedded hosts are explicitly limited. Existing static media parsing
is reused with an additional MP4Upload player.src pattern scoped to those hosts;
TopCinema's default host selection stays unchanged. Each resulting MP4/HLS/MKV
candidate must pass StreamProbe before it is offered. Deleted, gated or
incompatible servers are omitted; no CAPTCHA, DRM or access gate is bypassed.

Live inspection found valid film and episode pages, deleted servers, and a
public MP4Upload movie source whose range request returned HTTP 206 with an
MP4 ftyp header. This verifies media accessibility, not complete playback on
the owner's phone/TV. No new tests, workflows, emulator run or local Gradle
build were introduced. The main release and updater are unchanged.

# EgyBest private preview

The separate **تشغيل EgyBest** button uses the existing season/episode/server
screen and native Watch Hub player. The provider searches https://egytbest.live/
and resolves public post references afresh. Only source identity is persisted;
video URLs and session headers remain in memory.

Titles, production/debut or selected TMDB season year, and season/episode must
match. WordPress publication dates and episode URL slugs are not identity
proof. Search tries precise episode queries and title-only alternatives,
follows same-host next-page links (up to four pages per query), isolates failed
candidate pages, and checks episode links on matching season pages. Series
with an omitted season label are considered only for season one. No claim is
made that every title in the external catalogue has been individually tested.

All published HTTPS watch-server and download-page links on a matching page
are retained, independently of native-playback support. SourceWebLink keeps
website pages separate from SourceVariant video URIs. Compatible media use the
main player; original links open the existing in-app web viewer with clearly
labelled watch/download actions. Deleted/gated links may still fail in their
own website player. File-download gestures follow the viewer's existing browser
handling. No CAPTCHA, DRM or access gate is bypassed.

The previous implementation dropped a matched title when its hosts were not
on the native-parser allowlist, tried only four hosts and returned at most two
working variants. Those provider limits are removed. Native parsing uses an
explicit expanded host list and at most four concurrent host operations;
only signature-probed MP4/HLS/MKV candidates are presented as native streams.
WatchSources preserves matched pages even when none of their streams pass the
probe. This avoids falsely reporting that an existing movie is absent.

Live inspection reproduced Project Hail Mary (2026): nine watch-server URLs
and sixteen download-page URLs; its public Forafile MP4 returned HTTP 206 with
an ftyp header. Also reviewed Deep Blue Sea 2 (2018), My Adventures with
Superman episode pages, and Die Kaiserin season-three search results. These
are representative site/media checks, not on-device playback or exhaustive
catalogue verification. No new tests/workflows or local Gradle/emulator run.
The main release, login, Animeify and APK-update settings are unchanged.

## Playback reliability review

Live reinspection found HLS masters returning HTTP 200 while their child URLs
returned 403, and another path returning a PNG image as its first segment.
EgyBest now opts into a bounded HLS child-playlist/first-segment check, requiring
ordinary MPEG-TS or ISO media bytes (and a valid init prefix when present).
These checks sample stream accessibility; they do not prove an entire movie
plays or support image-wrapped/encrypted segments. Other providers retain their
existing default probe behavior. Public MP4 and ordinary HLS TS prefixes were
also confirmed during inspection, without downloading complete films.

Native choices now identify the embed by a stable URL hash and HLS role instead
of the website row number. Playback resolves only the selected server/variant,
and older saved row-based references remain supported. Returning from a website
link no longer disposes/restarts the search effect. All original website links
remain available separately and are not advertised as verified native streams.
The fix branch includes the newer stable TV Watch Next changes from main.
No new tests/workflows or local Android build/emulator were introduced.

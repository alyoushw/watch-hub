# TopCinema private source integration

**تشغيل TopCinema** appears directly below **تشغيل Moviz Time**, using the same
poster/seasons/episode/source dialog and the current Media3 player. Torrent,
Moviz Time, Animeify, login and the stable update channel remain unchanged.

The provider follows the supplied https://web6.topcinema.fan/ entry point to its
current canonical domain (topcinemaa.one during inspection). It searches the
website's film/series form using the title aliases and exact season/episode,
checks the post title and release-year field, and rejects contradictory linked
IMDb identifiers. Replay finds the post again by its numeric source identity.

The connector uses the public watch-page server selector. It parses direct
video fields and static Packer string tables without executing website scripts.
It handles compatible Uqload/StreamWish server formats and can inspect the same
direct/packed format on VideoTube/UpDown when those hosts allow access. Other
server formats are skipped. Sources must return real MP4/MKV/HLS signatures;
HTML pages, CAPTCHA pages, blocked hosts and deleted videos are not video URLs.
At most two working server variants are offered. Signed media URLs and session
headers are resolved afresh and are not stored in viewing history.

The existing source/player header handling passes cookies only to the original
manifest host and the non-secret user agent to CDN requests. No player
replacement, DRM handling, CAPTCHA handling, API credentials or new automatic
translation claim was introduced. Subtitles embedded in the source video remain
part of that video; separate Arabic subtitle coverage is not guaranteed.

Live endpoint inspection verified HLS master/media playlists and MPEG-TS video
bytes for Superman (2025) and Breaking Bad S01E01 on Uqload. Some other inspected
film links were deleted, and VideoTube/UpDown blocked this environment. These
checks do not claim playback testing of every title or on the owner's devices.
No tests/workflows were added or modified, and no emulator or local Gradle build
was run for this change. Only the independent private APK is intended for release.

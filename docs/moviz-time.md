# Moviz Time private source integration

The title details page has a separate **تشغيل Moviz Time** button immediately
below **تشغيل تورنت**, using the existing source screen's poster, season chips,
episode rows and source/quality dialog. It resolves only Moviz Time sources.
The original torrent path, Animeify integration and stable update channel stay
unchanged. This branch is for the independent private APK.

The connector searches the supplied website using the English search title or
the original title. It checks the page's title, production year, media type,
season and episode number. Conflicting linked IMDb identifiers are rejected.
Only the Vidhls HLS server format inspected on the live website is supported;
other embedded hosts and download landing pages are not passed to Media3.
Search currently checks at most two result pages per title.

Vidhls needs a fresh PHP session and standard playback request headers. The
connector obtains these from the public embedded player and builds the same
HLS proxy route used by that player. HLS manifests are checked by the existing
source probe before presentation and checked again during playback resolution.
The current Media3 player receives the selected HLS source and its transient
headers. Cookies are limited to the manifest host; CDN requests receive only
the non-secret user agent. Viewing history keeps source identity, not cookies,
temporary playlist URLs or generated CDN tokens. The session is resolved again
on replay/retry. No JWPlayer code or player licence key is copied into the app.

Endpoint inspection confirmed a real HLS master/media playlist and MPEG-TS
media bytes for a film example (The Girl with the Needle, 2024). The website's
own player also advanced playback. Series page inspection confirmed season 1
and episode-number buttons for Breaking Bad (2008). These checks do not claim
complete playback verification of every title on the owner's phone/TV.

Some embedded servers are stale or return 404. Such pages are not video sources
and are not offered by the video probe. Website availability or format changes
can affect this integration. Embedded/burned-in subtitles remain part of the
source video; no new Arabic speech translation or guaranteed caption track is
claimed. No DRM handling was added.

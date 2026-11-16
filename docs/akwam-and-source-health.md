# Akwam downloads as playback sources and source diagnostics

The **تشغيل أكوام** button is first in the source choices; torrent is last.
Existing player, TMDB seasons/episodes, bundled TMDB key, Animeify, TV integration
and the public APK-update channel remain. ArabLionz is not included.

The supplied akw.cam watch page explicitly had zero watch servers while its
download page had fourteen hosting-page links. AkwamSourceProvider follows the
observed `/search?q=`, `.br-card-link`, episode-grid and download-row links.
The slug suffix `s1` in the supplied URL means episode one here; it is not
treated as season metadata. Title/year/episode matching and host filename
checks prevent selecting another episode. Films must match the year in the
title; yearless series pages require a unique matching TMDB search identity.
Unconfirmed identities remain website-only and are labelled accordingly.
Upload dates are never production years. Stable detail slugs are source IDs;
temporary media URLs are refreshed at playback.

Only ordinary MP4 URLs and the observed public Lulu download-player format are
resolved natively. Lulu's static Packer strings reuse the existing parser; no
site JavaScript is executed. The filename must match title/year or season and
episode. The resulting media must pass signature and HLS child/first-segment
checks. Other download pages remain separately labelled website alternatives,
including interactive/CAPTCHA hosts; no gate or DRM bypass is added. The main
player streams a checked result without requiring a full-file download.

## Verification completed before starting an APK build

HTTP requests and actual FFmpeg decoding of the first video frame succeeded
for the supplied Sunshine Through the Rain S01E01 download source, Diabolic
(2025), and Being Heumann (2026). The check decoded a short beginning, not an
entire film and not an Android-device session. All three Lulu links sampled for
Somebody (2025) returned pages with no extractable video; this does not establish
that every host for that title is broken. These failures are retained as
unverified rather than described as a missing movie. No CAPTCHA was solved or
access check bypassed. No temporary media URL is committed.

## Consistent diagnostics for website providers

StreamProbe exposes typed results: playable, unavailable HTTP response,
blocked/access-limited, network failure, unsupported payload, or unverified.
HLS child-request failures retain the real HTTP status. Signature-compatible
responses no longer conceal a blocked child or image payload. Familiar challenge
pages are reported as verification failures instead of empty search results.

EgyBest, Moviz Time and TopCinema retain a confirmed matching post even when
there is no working native variant; hosting failures are recorded per server.
A search with no confirmed match says exactly that, with a manual website-search
action, rather than asserting that the title does not exist. Network/search
failures are shown separately. Possible identity matches are not offered as
native playback. Torrent index entries are labelled as indexed sources rather
than claimed to have passed video-start validation. A start check does not
guarantee future server availability or an entire film's playback.

No new tests, CI/workflow changes, local Gradle build or emulator run were added.
The existing unit/lint and APK build workflow is used after the live media checks.

## Declared website playback pages

Download-page iframes provide additional website viewing choices: MixDrop's
observed `/f/<id>` page declares `/e/<id>`, and Lulu's `/d/<id>` page declares
`https://luluvid.com/e/<id>`. Collect only declared HTTPS iframe URLs on the
known host families with the same file ID; never synthesize a playback route.
Keep their original server order and remove duplicates. Other hosts without
an exposed playback page retain their original download-page action.

The selection dialog displays verified native streams first, website viewing
pages next, original download pages next, and diagnostic details afterwards.
Website pages open the existing in-app browser, independently of Media3 streams.
Before building this change, the declared Lulu viewing page decoded a video
frame successfully for the supplied episode; the Diabolic viewing page returned
HTTP 200 with the matching filename. MixDrop's declared viewing page returned
HTTP 200 after its normal redirect, but its full playback was not verified.

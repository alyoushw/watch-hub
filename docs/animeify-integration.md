# Animeify integration (Android 0.23)

## Baseline and scope

Based on `ccr-3b14310b-u6y40j` at `d82d9ae`, which contains the player,
downloads, accounts/sync, and update notifications used by the published build 129.
`main` is an older application and must not replace this baseline during merging.
The owner explicitly authorized use of the Animeify source in this session.

The existing application, navigation, ExoPlayer/Media3 player, audio decoders,
buffering controls, Xtream account behavior, downloads, subscription tracking,
and build-N update protocol are retained. No new application or player is created.

## Source contract verified against Animeify 3.2.0

Reference APK: <https://animeify-app.com/downloads/Animeify_v3.2.0.apk>

SHA-256: `ac89ac85751b73c9767824c41b4a37465b0d4bbb8ea5f612a9544ba58eb90d09`

Read-only form POST requests to `https://animeify.net/animeify/apis_v4/`:

| Path | Parameters besides Token | Response |
| --- | --- | --- |
| `anime/load_anime_list_v2.php` | UserId=0, Language=ar, FilterType=SEARCH, FilterData, Type, From=0 | Title array |
| `anime/load_related_anime.php` | UserId=0, Language=ar, RelationId, RelatedId | Related seasons/films array |
| `episodes/load_episodes.php` | AnimeID | Episode array |
| `anime/load_servers.php` | UserId=0, AnimeId, Episode, AnimeType | CurrentEpisode and adjacent episodes |

Never interchange AnimeId (source string), Id (source database id), MAL id, or TMDB id.
`MalId_org` is preferred over `MalId`, which may contain the sentinel `tempres`.
Known MAL matches select automatically. TMDB titles show source search results for
explicit selection; they do not auto-play approximate title matches.

Both TMDB anime details and retained Jikan details offer the Animeify watch button.
Related entries display their actual source season and title, including separately
catalogued films. Episode lists use lazy cards for large series and TV use.

Supported server adapters: MediaFire HD/SD/FHD MP4 (`FRLink`, `FRLowQ`, `FRFhdQ`),
Sendvid HD/SD, and OK's available stream. Only non-empty source fields are offered;
an advertised host may still have removed an old video. Unsupported legacy hosts
(Mega, Drive, obsolete file hosts) are not claimed as supported. Failed hosts show
an error; select another server from the title page.

Arabic is supplied by the translated source video (including burned-in subtitles)
or embedded subtitle tracks selected by the existing player. No generated subtitle
translation or guaranteed Arabic availability is claimed for every source file.

## Playback and persistence

Queues and watch history identify an episode by AnimeId + episode, independent of
server choice. An additive `animeRef` holds stable identifiers only. Resolving the
source on playback, retry, and resume obtains a fresh CDN URL. Those URLs are kept
only in the in-memory player queue and never in preferences or cloud history.
Resume/next/previous/intro/end-credit behavior uses the existing player. Animeify
progress appears in the existing horizontal continue-watching cards, alongside
eligible Xtream entries.
The optional source reference is preserved by Android/cloud serialization and the
web sync field whitelist. Web playback of the new source is outside this Android scope.

## Build configuration

Set repository Actions secret `WATCHHUB_ANIMEIFY_TOKEN` to the owner's API client
token. It is injected into BuildConfig by release builds, not committed to Git.
Local builds can set the same environment variable. No Animeify user account or
credentials are imported. Missing configuration produces a clear source error,
and publishing is blocked until the release build includes the source token.

Run `./gradlew testDebugUnitTest lintDebug assembleRelease` with Java 17 and SDK 35.
GitHub Actions runs unit tests/lint and emulator checks before building and uploads
verification reports. Public APK artifacts are disabled for private previews. Pushes
to the feature branch do not publish; manual private runs create draft prereleases.
Stable release tags retain build-N, versionCode remains 100 + run number, regular
versionName is 0.23.N, and the original signing-key configuration remains unchanged.
Release tags now explicitly identify the built commit instead of relying on the default branch.

## Update review

The original updater implementation is retained for the regular application: its
6-hour WorkManager task, foreground checks, notification channel/permission,
FileProvider installer and build-N parsing are unchanged. Only preview builds skip
checks and notifications. The stable delivery uses the original package ID and
signing configuration, so an existing installation can receive the new build.

## Verification

Live read probes returned HTTP 200 for catalogue search, related seasons, the 220
Naruto episodes, and current episode servers. MediaFire returned a page containing
the expected MP4 download link. These are point-in-time checks, not a guarantee that
all advertised host links remain available. Synthetic/trimmed fixtures contain no
client token or temporary CDN links.

Unit tests cover title/MAL mapping, special episodes/sorting, available qualities,
MediaFire link decoding/host validation, source request shapes and URL refresh,
missing selected quality, source errors, backward-compatible history, and saved
navigation payloads. The API 30 emulator suite checks launching the existing Home screen and actual MP4
playback/progress persistence with a generated local sample. A third test exercises
live Animeify search, episodes, server resolution and playback when the owner token
is configured; it is explicitly skipped otherwise. Publication separately requires
that token. Subtitle selection, TV remote interaction, and Android 13+ notification
permission behavior need additional manual device checks and are not claimed as
covered by the API 30 smoke suite.


## Private preview distribution

The initial delivery was a private test APK. That build remains available separately. Feature
branch builds use `com.alyoushw.watchhub.animepreview` and the launcher label
`Watch Hub Anime (خاص)` with a `-preview` version suffix. This allows installation
alongside the existing app, with independent local settings/data. The preview does
not poll or install main-channel updates; the regular package retains its updater.

A manual Actions run with `distribution=private` creates only a draft prerelease
asset named `WatchHub-Anime-Preview.apk`, with `make_latest=false`. Draft assets
require a repository writer's GitHub login. They are excluded from `/releases/latest`
and do not notify existing users. Main-channel delivery is handled separately as described below.

## Revised watch flow and downloads

The title page now has a gold `تشغيل أنمي فاي` watch action below the existing
provider choices. It opens a separate saved navigation route with the title's
poster/story, related seasons, and episode cards. The episode card component is
shared with the IPTV series page, retaining its colours, RTL layout, watched states
and remote focus. A top download button queues the selected season; long-pressing
an episode downloads that episode after selecting a server/quality.

Animeify episodes now appear in the existing horizontal Continue Watching row
alongside IPTV entries, with an Animeify provider label. Series and movie filters
remain separate, and long-pressing an Animeify card opens its season/episode page.

Downloads use the existing serial download worker and pause/resume controls.
Animeify download entries persist an empty URL and the stable episode reference.
The worker resolves a fresh source URL each time a download starts or retries;
completed local files bypass source resolution in the existing player.

Additional verification covers saved navigation payloads, the watch-action/episode
page/download-picker flow with Honey Lemon Soda, actual source download bytes and
pause support, and playback of a completed local file with an Animeify reference.
UI screenshots are collected with the device reports. The revised preview passed
all 15 unit tests, lint and all 6 emulator tests on run 150.

## Main application delivery

The owner subsequently requested delivery as an update to the existing app.
Integrate into the active `ccr-3b14310b-u6y40j` branch, which contains the current
player, accounts and downloads; the default `main` branch is older. A manual
`distribution=stable` run on the active branch builds `com.alyoushw.watchhub` and
publishes `WatchHub.apk` under the existing build-N update protocol after checks.

Before publication, `verify-stable-apk.py` compares the new signed APK to the latest
published APK: package IDs and signer certificates must match and versionCode must
increase. This blocks an incompatible APK from becoming the update. Account
configuration/authentication/session code remains identical to the active baseline;
the CloudSync additions only serialize the new episode reference. Unlike the
preview package, the regular package retains the Android OAuth identity documented
in README.md. The emulator also verifies the existing live account configuration
and Firebase initialization. Completing Google consent with an actual user account
remains a manual device check and is not claimed as an automated test.

A `distribution=validation` run builds the original application identity and runs
the same signing/version comparison, but saves the APK only in a private draft
`stable-candidate-N`. It cannot publish a stable release or upload a public APK
artifact. This makes the exact upgrade APK reviewable before merge/publication.

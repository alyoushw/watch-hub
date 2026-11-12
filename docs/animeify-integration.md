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

Both TMDB anime details and retained Jikan details offer the source panel. Related
entries display their actual source season and title, including separately catalogued
films. Episode lists paginate at 30 items for large series and TV use.

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
Resume/next/previous/intro/end-credit behavior uses the existing player. The anime
Home tab has its own continue-watching section; old Xtream entries remain separate.
The optional source reference is preserved by Android/cloud serialization and the
web sync field whitelist. Web playback of the new source is outside this Android scope.

## Build configuration

Set repository Actions secret `WATCHHUB_ANIMEIFY_TOKEN` to the owner's API client
token. It is injected into BuildConfig by release builds, not committed to Git.
Local builds can set the same environment variable. No Animeify user account or
credentials are imported. Missing configuration produces a clear source error,
and publishing is blocked until the release build includes the source token.

Run `./gradlew testDebugUnitTest lintDebug assembleRelease` with Java 17 and SDK 35.
GitHub Actions runs unit tests/lint before building and uploads verification reports
and `WatchHub-apk`. Pushes to the feature branch do not publish. After verification,
a manual run on the reviewed branch follows the existing release mechanism.
Release tags remain build-N, versionCode remains 100 + run number, versionName is
0.23.N, and the original signing-key configuration remains unchanged. Release tags
now explicitly identify the built commit instead of relying on the default branch.

## Update review

The 6-hour WorkManager task, notification channel/permission, FileProvider installer,
and foreground checks remain in place. Fixes cover dismissed dialogs being shown
again after a manual check, notification delivery failures, retaining known updates
during temporary API failures, and local build number 0 being older than CI builds.
Release parsing is tested against the canonical WatchHub.apk and build-N protocol.

## Verification

Live read probes returned HTTP 200 for catalogue search, related seasons, the 220
Naruto episodes, and current episode servers. MediaFire returned a page containing
the expected MP4 download link. These are point-in-time checks, not a guarantee that
all advertised host links remain available. Synthetic/trimmed fixtures contain no
client token or temporary CDN links.

Unit tests cover title/MAL mapping, special episodes/sorting, available qualities,
MediaFire link decoding/host validation, source request shapes and URL refresh,
missing selected quality, source errors, backward-compatible history, and release
update parsing/version comparisons. The API 30 emulator suite checks launching the existing Home screen and actual MP4
playback/progress persistence with a generated local sample. A third test exercises
live Animeify search, episodes, server resolution and playback when the owner token
is configured; it is explicitly skipped otherwise. Publication separately requires
that token. Subtitle selection, TV remote interaction, and Android 13+ notification
permission behavior need additional manual device checks and are not claimed as
covered by the API 30 smoke suite.


## Private preview distribution

The requested delivery is a private test APK, not a main-channel release. Feature
branch builds use `com.alyoushw.watchhub.animepreview` and the launcher label
`Watch Hub Anime (خاص)` with a `-preview` version suffix. This allows installation
alongside the existing app, with independent local settings/data. The preview does
not poll or install main-channel updates; the regular package retains its updater.

A manual Actions run with `distribution=private` creates only a draft prerelease
asset named `WatchHub-Anime-Preview.apk`, with `make_latest=false`. Draft assets
require a repository writer's GitHub login. They are excluded from `/releases/latest`
and do not notify existing users. The branch/PR remains separate and is not merged
into the current or default branch for this delivery.

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
UI screenshots are collected with the device reports. Delivery remains a separate
private preview; neither application branch is merged or its main release updated.

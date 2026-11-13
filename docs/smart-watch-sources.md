# Smart sources preview

This feature is isolated on `feat/smart-watch-sources`. The preview uses application ID
`com.alyoushw.watchhub.smartpreview`, keeps the launcher name **Watch Hub**, and must
not be promoted through the original application's update channel.

## Automatic index search

The Pirate Bay search is enabled by default in this private preview and requires
no catalogue. Opening a detail page searches its video index via `apibay.org` with
the original title plus release year, or exact season/episode. It shows up to 20
matching results, ordered by reported seeders, with full release names and sizes.
The setting can disable index search. Existing saved choices are retained.

Movies require exact title/year; contradictory IMDb IDs and non-movie categories
are rejected. Episodes require exact coordinates and matching IMDb ID, or title,
series year and coordinates when the index has no IMDb data. Combined episodes,
season packs and reported zero-seeder results are excluded. This conservative
matching can omit releases whose names lack required identity information.

Selecting a result repeats the index query and finds that exact entry again by
ID and info hash, then checks content identity. The client honours HTTP 429
Retry-After cooldowns instead of retrying automatically. Its Magnet goes to the existing separate preparation engine;
it is not sent to the player as a direct video URL. Reported peers are historical
index metadata and cannot guarantee a reachable swarm or successful preparation.
Index listings are shown as source pages, not licensing declarations. The app does
not establish distribution rights from an index listing.

## Optional source catalogue

Settings → source catalogue accepts an owner-controlled HTTP(S) endpoint returning a
JSON array. Search sends `tmdb_id`, `type` (`movie`/`tv`), `original_title`, `year`, and,
for series, `season` and `episode`. Optional `imdb_id` helps index matching. Every
returned entry must match the query exactly, including the original title, year and
episode. A static JSON file can also serve several entries; unmatched ones are ignored.

Example response for the legally redistributable Blender film Sintel:

```json
[
  {
    "id": "sintel",
    "providerId": "owner",
    "providerName": "My licensed catalogue",
    "query": {
      "type": "MOVIE",
      "tmdbId": 45745,
      "originalTitle": "Sintel",
      "year": 2010
    },
    "variants": [
      {
        "id": "mp4",
        "label": "MP4",
        "transport": "MP4",
        "url": "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4"
      }
    ],
    "subtitles": [],
    "attribution": "Sintel © Blender Foundation · CC BY 3.0",
    "licenceUrl": "https://durian.blender.org/sharing/"
  }
]
```

For TV use `"type":"TV"` plus numeric `season` and `episode`. Formats are `MP4`,
`HLS`, `MKV`, `TORRENT` and `MAGNET`. Subtitle entries contain `id`, `label`, `language`,
`url` and `format` (`srt` or `vtt`). Supply only content and subtitles authorized for
redistribution. Catalogue licensing metadata documents the provider's declaration;
the app cannot independently establish ownership. No entitlement or DRM bypass is
implemented. Direct streams are probed before displaying the play action.

History and cloud sync store source identity and variant ID, not expiring video URLs.
The catalogue is requested again before playback. Updating its episodes/links does
not require an APK update. This is discovery when opening the details/player, not a
background daily update service. Existing Animeify resolution remains separate.

## Torrent preparation

Torrent/Magnet values are never passed to ExoPlayer as video URLs. A separate engine
fetches metadata, selects the exact episode, downloads and verifies the chosen file,
then opens the local MP4/MKV in the existing player. This first implementation waits
for the complete selected file; it is not sequential streaming. Preparation can be
cancelled by leaving the player and is limited to 30 minutes. Pure v2-only torrents
are not supported. Downloaded files currently remain in the app-private `source-torrents` directory until app data is cleared.

Tests generate an original synthetic video and torrent with a local web seed;
index search and selected-result resolution are also tested without a catalogue.

## Subtitles

The existing Media3 player retains quality, audio, subtitle and playback-position
controls. External UTF-8 SRT/WebVTT can be supplied by the catalogue, an HTTP(S) URL,
or Android's document picker. Arabic text translation is opt-in using on-device
ML Kit models downloaded on first use. The original cue remains visible if a model
or translation fails. Timing follows decoded player cues, supports a 0–5 second
positive delay, and clears on seeks. Font size, colour and background are configurable.

There is no audio transcription or live speech translation in this preview. Such an
option needs a separate provider, explicit cost/latency decisions and device tests.
The Android smoke test verifies actual Arabic caption rendering, timing and seek clearing.

## Verification

Local checks: `testDebugUnitTest lintDebug compileDebugAndroidTestKotlin` with
`WATCHHUB_PREVIEW=true` (27 JVM tests). GitHub Actions additionally runs Android 11
smoke tests for the existing player, Animeify, TV gestures, opt-in preload, direct
MP4/HLS/MKV playback, torrent/Magnet preparation and on-device Arabic subtitle
translation. A private APK is created only after those checks pass.

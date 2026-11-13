# Playable films and complete television seasons

Paste this URL into Watch Hub's optional source catalogue setting and save:

https://raw.githubusercontent.com/alyoushw/watch-hub/refs/heads/feat/smart-watch-sources/docs/examples/open-catalogue.json

Search for a title below, then open the existing «تشغيل تورنت» watch-source page
and select the named catalogue source. These entries use direct MKV/HLS/MP4
playback without torrent peers or downloading the whole video first. This JSON
works with source-playback preview 165 without an APK update. The separate TMDB
browsing preview does not include source playback.

| Title | Year | Included | TMDB ID |
| --- | --- | --- | --- |
| Night of the Living Dead | 1968 | Complete film, MP4 | 10331 |
| His Girl Friday | 1940 | Complete film, MP4 | 3085 |
| The General | 1926 | Complete film, MP4 | 961 |
| Nosferatu | 1922 | Complete film, MP4 | 653 |
| Sintel | 2010 | Complete short, MKV | 45745 |
| Big Buck Bunny | 2008 | Complete short, HLS | 10378 |
| Pioneer One | 2010 | Season 1, all 6 episodes, MP4 | 33050 |
| Sherlock Holmes | 1954 | Season 1, all 39 episodes, MP4 | 6560 |

51 source entries: 6 films (including 2 shorts) and 45 episodes across 2 complete
seasons. This is a collection of playable works, not every title in TMDB.

## Provenance and reuse

- The four classic films use Internet Archive copies; each entry links to its
  source item. Preserve original credits. The General uses TMDB's 1926 year and
  Nosferatu its German original title for exact source matching.
- Sherlock Holmes: Sheldon Reynolds / Guild Films, Ronald Howard series (1954),
  public-domain-marked item: https://archive.org/details/SherlockHolmes1954
  Episode order: https://www.tvmaze.com/shows/22618/sherlock-holmes/episodes
  TMDB identity: https://www.wikidata.org/wiki/Q2570112
- Sintel: official Blender Foundation MKV, TMDB 45745, CC BY 3.0.
  https://durian.blender.org/sharing/
- Big Buck Bunny: Blender Foundation, TMDB 10378, CC BY 3.0; public Mux HLS
  demonstration stream. https://peach.blender.org/about/
- Pioneer One: Josh Bernhard and Bracey Smith, TMDB 33050 / IMDb tt1748166;
  original CC BY-NC-SA 3.0 conditions apply. This sample is for personal,
  non-commercial viewing. Preserve the credits and attribution. Archive item
  uploads may declare different metadata; this catalogue uses the original,
  more restrictive non-commercial licence rather than treating that metadata as
  permission for commercial distribution.
  https://liris-accede.ec-lyon.fr/database.php
  https://creativecommons.org/licenses/by-nc-sa/3.0/
  Episode/source identity: https://thetvdb.com/series/pioneer-one

## Verification and limits

The 43 newly added MP4 URLs returned video data with MP4 signatures during
bounded HTTP reads. Film/episode durations were checked in archive metadata;
these are endpoint checks, not full playback tests on the owner's devices.
Sherlock Holmes has 39 automatic English WebVTT tracks; His Girl Friday
includes automatic English SRT. All 40 caption URLs returned subtitle text.
Automatic captions may
contain errors. Arabic coverage and sync have not been verified for this
collection. No Arabic tracks are invented.

Verified small HTTP reads on all six Archive MP4s (206 responses, MP4 ftyp), the
Sintel MKV (EBML) and Big Buck Bunny HLS playlist. The original 8 entries were
also validated with Watch Hub's Kotlin serializer and compatibility validator.
These are endpoint checks, not a full watch-through of every film/episode.
Canonical archive.org download URLs are retained rather than temporary redirected
CDN URLs. Hosts may change availability or access policy. Arabic text tracks are
not guaranteed; the existing player can attach SRT/WebVTT and translate available
text tracks. No speech translation is claimed.

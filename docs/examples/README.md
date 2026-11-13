# Ready-to-use personal viewing catalogue

Paste this URL into Watch Hub's optional source catalogue setting and save:

https://raw.githubusercontent.com/alyoushw/watch-hub/refs/heads/feat/smart-watch-sources/docs/examples/open-catalogue.json

Search for **Sintel (2010)**, **Big Buck Bunny (2008)** or **Pioneer One (2010)**,
then open the existing «تشغيل تورنت» watch-source page. Sources named
«كتالوج التجربة» use direct MKV/HLS/MP4 playback; they do not require torrent peers.
Pioneer One includes season 1, episodes 1–6. This JSON works with preview 165
without an APK update. It is a small example catalogue, not a general movie API.

## Provenance and reuse

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

Verified small HTTP reads on all six Archive MP4s (206 responses, MP4 ftyp), the
Sintel MKV (EBML) and Big Buck Bunny HLS playlist. The same Kotlin serializer and
compatibility validator used by Watch Hub are used to validate these entries.
These are endpoint checks, not a full watch-through of every film/episode.
Canonical archive.org download URLs are retained rather than temporary redirected
CDN URLs. Hosts may change availability or access policy. Arabic text tracks are
not guaranteed; the existing player can attach SRT/WebVTT and translate available
text tracks. No speech translation is claimed.

# Kora website page (private preview)

The **كوره** navigation entry opens https://majed-koora.live/ inside Watch Hub.
It uses the public website and its normal match/watch navigation, not the
separately installed Majed Sport APK. The website retains its normal watch flow,
access rules, advertising flow and server-side updates. No API key or native-app
attestation from the uploaded APK is copied into Watch Hub.

The page provides back, site home, refresh and HTML video fullscreen controls.
User-initiated secondary windows are attached to the visible browser container,
with their own normal navigation, shared browser clients and focus/close events.
The active window is shown in front; background windows are paused/hidden.
Back or the close-window action restores the previous window. Returning to the
site home closes secondary windows, and leaving Kora destroys every window.
This replaces the original temporary, unattached popup view and parent-page
redirection, which could lose the website's window/opener flow or leave media
in an unseen WebView. Viewport/overview settings follow browser page sizing.
First-party storage and third-party player cookies support the site's normal
session flow. Web navigation stays in the page for HTTP(S); unsupported app-only
links display a message. User-initiated file downloads open a browser. No native
JavaScript bridge, local file access, content-provider access or TLS-error
bypass is added. Navigation away destroys the WebView; lifecycle pause/resume
is forwarded. The side navigation rail can scroll on short car/TV screens to
keep all entries accessible after adding the new page.

Live browser inspection with a mobile WebView-style user agent loaded seven
match cards and followed a watch link to the website's article/player flow.
The public site uses /api/v1/matches, distinct from the APK's guarded /api/matches.
This verifies page loading and navigation, not complete match playback or every
streaming host. The normal website may require user interaction before video
playback; no gate or DRM is bypassed.

After normal web authorization, eligible HLS/MP4/MKV requests from the known
player.majedjs.site player can open the existing Watch Hub Media3 player.
The observed request is checked with StreamProbe and its session headers are
passed in memory. The web player is removed before native playback to prevent
overlapping audio. Temporary URLs are not saved in watch history or cloud sync.
Unsupported sources remain in the website player. No URL decryption, DRM
handling or app-attestation bypass is introduced. Native handoff requires
owner testing on a real device; browser inspection alone did not verify it.

Existing Animeify, torrent, Moviz Time, TopCinema, login, player and APK-update
behavior remain. The new page is delivered only through the separate private
preview. No tests or workflows were changed, and no local Gradle/emulator run
was introduced for this change.

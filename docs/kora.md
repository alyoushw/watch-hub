# Kora website page (private preview)

The **كوره** navigation entry opens https://majed-koora.live/ inside Watch Hub.
It uses the public website and its normal match/watch navigation, not the
separately installed Majed Sport APK. The website retains its own video player,
access rules, advertising flow and server-side updates. No API key or native-app
attestation from the uploaded APK is copied into Watch Hub.

The page provides back, site home, refresh and HTML video fullscreen controls.
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
playback; no gate or DRM is bypassed. No claim is made that the existing native
Watch Hub player is used for this website integration.

Existing Animeify, torrent, Moviz Time, TopCinema, login, player and APK-update
behavior remain. The new page is delivered only through the separate private
preview. No tests or workflows were changed, and no local Gradle/emulator run
was introduced for this change.

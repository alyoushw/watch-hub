# ArabLionz source integration

The **تشغيل عرب ليونز** button is first in the detail-page source choices;
torrent is below the existing sources and Animeify. The source uses the same
TMDB season/episode UI, server dialog, existing native player and website viewer.

Public search uses the observed WordPress `s` parameter and `.BlockItem h4`
titles. Exact title aliases, release/debut or selected-season year, and episode
metadata must match. The known `DC's` title prefix is an optional alias, allowing
TMDB's DC's Legends of Tomorrow to match the site's Legends of Tomorrow title.
Fallback traversal follows the actual `.SeasonsList` and `.EpisodesList` links.
Only WordPress post identity is persisted; server URLs are fetched on replay.

The supplied season-one episode-13 page returned HTTP 200, year 2016 and the
correct episode title. Its declared `?watch=1` and `?download=1` pages returned
five `.cwd-server-btn[data-server-url]` watch choices and five
`a.cwd-download-btn[href]` download choices. Superman (2025) search/detail pages
also returned HTTP 200 with the matching title, year and watch link. The old
Server.php JavaScript is not used: current watch controls expose their URLs
directly and differ from the unused legacy ServerItem script.

All five observed watch hosts were subdomains of movnet.site. Requests from
this execution environment were rejected by its connection proxy with 403
before the player pages could be read. No origin-IP, TLS or network-control
bypass was attempted. Therefore **actual stream playback is unverified**.
Native resolution tries the existing public media parser only for the declared
movnet.site hosts, and offers a native URL only if StreamProbe validates its
signature plus ordinary HLS child-playlist/first-segment accessibility. This
parser path could not be verified against these blocked player hosts.

Original watch/download links remain labelled separately as unverified website
links, with the original watch page available for the site's normal flow.
The main release is unchanged during this private preview. Bundled TMDB key,
TV Watch Next, other sources and the APK updater remain present. No new tests,
workflows, local Gradle build or Android emulator run were introduced.

# Phone player casting

The phone player has a Cast icon and «كروم كاست» action at the physical top left.
It offers Google Cast and DLNA. TV, automotive UI mode, and automotive hardware
do not initialize this controller or show the action.

Google Cast uses the official Cast framework and Default Media Receiver, with
the SDK's MediaRouter device chooser. The local video pauses after the receiver
accepts the load; SDK status/load errors are shown. DLNA discovers MediaRenderer
and AVTransport services through SSDP on Wi-Fi/Ethernet IPv4, reads the device's
description, and sends SetAVTransportURI/Play/Pause/Seek/Stop SOAP commands.
Discovery and transport failure are distinct from an empty device list.

An unexported mediaPlayback foreground service relays this session over the
phone's Wi-Fi address. Random session URLs expose only registered resources;
provider URLs, cookies and authorization headers stay in memory. The relay
forwards byte ranges, supports local downloaded files and loopback torrent
streams, and rewrites HLS variant, segment, audio, map and key URIs. Session
headers follow the same host restriction as the native player. It does not
decrypt media, transcode codecs, or turn a Miracast-only device into DLNA.

The service keeps the relay available while the phone screen is locked and
provides an explicit stop notification. Ending playback, leaving the player,
or changing networks tears down the relay. Receiver controls replace local
controls during casting to avoid duplicate playback; returning resumes at
the receiver's reported position when available. External subtitle and
multi-episode remote queue support are outside this change.

The receiver must support the video's container/codecs and be reachable from
the phone on the same LAN. Google Cast also requires Google Play services.
Hardware end-to-end verification needs a real Cast receiver and a real DLNA
renderer on that LAN; no such devices are attached to the cloud workspace.
SDK signatures were checked against the actual library artifacts. No tests,
CI steps or emulator runs were added; the release uses the existing checks.

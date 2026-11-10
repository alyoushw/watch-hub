// Keeps Watch Hub's own files on the device, so the app opens at once even while the free
// server is still waking up (instead of the host's loading page). Files are refreshed in the
// background on every visit; a reply that is not ours (e.g. the host's wake-up page) is ignored.
const CACHE = "watchhub-v11";
const SHELL = ["./", "index.html", "styles.css", "js/app.js", "js/store.js", "js/tmdb.js", "js/xtream.js", "js/player.js", "js/cloud.js", "js/smart.js", "js/translate.js", "js/foryou.js",
  "manifest.webmanifest", "icon.svg", "icon-180.png", "icon-512.png"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).catch(() => {}).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

/** Is this reply really our file (and not the host's wake-up or error page)? */
async function genuine(req, res) {
  if (!res || !res.ok || res.type === "opaque") return false;
  const type = res.headers.get("content-type") || "";
  const path = new URL(req.url).pathname;
  if (path.endsWith(".js")) return type.includes("javascript");
  if (path.endsWith(".css")) return type.includes("css");
  if (type.includes("text/html")) return (await res.clone().text()).includes('id="view"');
  return true;
}

self.addEventListener("fetch", (e) => {
  const req = e.request;
  const url = new URL(req.url);
  if (req.method !== "GET" || url.origin !== location.origin) return;
  // Server routes (proxy, account settings, sign-in links) always go to the server.
  if (/^\/(proxy|pair|auth|config\.json|\.well-known)/.test(url.pathname)) return;
  if (req.mode === "navigate" && url.pathname !== "/" && !url.pathname.endsWith("/index.html")) return;
  const key = req.mode === "navigate" ? "index.html" : req;
  e.respondWith((async () => {
    const cache = await caches.open(CACHE);
    const cached = await cache.match(key, { ignoreSearch: req.mode === "navigate" });
    const update = fetch(req).then(async (res) => {
      if (await genuine(req, res)) await cache.put(key, res.clone());
      return res;
    }).catch(() => null);
    if (cached) {
      e.waitUntil(update);
      return cached;
    }
    return (await update) || new Response("offline", { status: 503 });
  })());
});

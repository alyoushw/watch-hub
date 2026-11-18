// Keeps Watch Hub's own files on the device, so the app opens at once even while the free
// server is still waking up (instead of the host's loading page). Files are refreshed in the
// background on every visit; a reply that is not ours (e.g. the host's wake-up page) is ignored.
const CACHE = "watchhub-v23";
const SHELL = ["./", "index.html", "styles.css", "js/app.js", "js/store.js", "js/tmdb.js", "js/xtream.js", "js/player.js", "js/cloud.js", "js/smart.js", "js/translate.js", "js/foryou.js", "js/fouru.js", "js/features.js", "js/sources.js", "js/downloads.js",
  "manifest.webmanifest", "icon.svg", "icon-180.png", "icon-512.png"].map((path) => /\.(js|css)$/.test(path) ? `${path}?v=20261118b` : path);

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
  if (url.origin !== location.origin) return;
  if (url.pathname.startsWith("/offline/")) { e.respondWith(offlineVideo(req).catch(() => new Response("offline video unavailable", { status: 404 }))); return; }
  if (req.method !== "GET") return;
  // Server routes (proxy, account settings, sign-in links) always go to the server.
  if (/^\/(sources|fouru|proxy|pair|auth|config\.json|\.well-known)/.test(url.pathname)) return;
  if (req.mode === "navigate" && url.pathname !== "/" && !url.pathname.endsWith("/index.html")) return;
  const key = req.mode === "navigate" ? "index.html" : req;
  e.respondWith((async () => {
    const cache = await caches.open(CACHE);
    const cached = await cache.match(key, { ignoreSearch: req.mode === "navigate" });
    // Versioned modules are immutable within a release. Only navigation and unversioned scripts need the network first.
    const executable = req.mode === "navigate" || url.pathname.endsWith(".js") && !url.searchParams.has("v");
    const update = fetch(req).then(async (res) => {
      if (await genuine(req, res)) await cache.put(key, res.clone());
      else if (cached) return cached;
      return res;
    }).catch(() => null);
    if (cached && !executable) {
      e.waitUntil(update);
      return cached;
    }
    if (cached) return (await update) || cached;
    return (await update) || new Response("offline", { status: 503 });
  })());
});

async function offlineVideo(req) {
  const key = decodeURIComponent(new URL(req.url).pathname.split("/")[2] || "");
  const db = await new Promise((resolve, reject) => { const r = indexedDB.open("watchhub-downloads", 1); r.onupgradeneeded = () => { r.result.createObjectStore("items"); r.result.createObjectStore("chunks"); }; r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error); });
  if (!db.objectStoreNames.contains("items")) { db.close(); return new Response("not found", { status: 404 }); }
  const get = (store, run) => new Promise((resolve, reject) => { const r = run(db.transaction(store).objectStore(store)); r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error); });
  const item = await get("items", (s) => s.get(key));
  if (!item || item.state !== "complete") { db.close(); return new Response("not found", { status: 404 }); }
  const size = item.bytes, rawRange = req.headers.get("range"), range = rawRange && /^bytes=(\d*)-(\d*)$/.exec(rawRange);
  let from = 0, to = size - 1;
  if (rawRange) {
    if (!range) { db.close(); return new Response(null, { status: 416, headers: { "content-range": `bytes */${size}` } }); }
    if (range[1]) { from = Number(range[1]); to = range[2] ? Math.min(to, Number(range[2])) : to; }
    else from = Math.max(0, size - Number(range[2]));
    if (from > to || from >= size) { db.close(); return new Response(null, { status: 416, headers: { "content-range": `bytes */${size}` } }); }
  }
  const headers = { "content-type": item.type || "video/mp4", "content-length": String(to - from + 1), "accept-ranges": "bytes", "cache-control": "no-store" };
  if (range) headers["content-range"] = `bytes ${from}-${to}/${size}`;
  if (req.method === "HEAD") { db.close(); return new Response(null, { status: range ? 206 : 200, headers }); }
  const chunks = await new Promise((resolve, reject) => {
    const out = [], r = db.transaction("chunks").objectStore("chunks").openCursor(IDBKeyRange.bound([key, 0], [key, to]));
    r.onerror = () => reject(r.error); r.onsuccess = () => { const c = r.result; if (!c) return resolve(out); if (c.key[1] + c.value.size > from) out.push({ start: c.key[1], blob: c.value }); c.continue(); };
  }); db.close(); let index = 0, reader = null;
  const stream = new ReadableStream({ async pull(controller) {
    while (true) {
      if (!reader) { const c = chunks[index++]; if (!c) { controller.close(); return; } reader = c.blob.slice(Math.max(0, from - c.start), Math.min(c.blob.size, to - c.start + 1)).stream().getReader(); }
      const { value, done } = await reader.read(); if (done) { reader = null; continue; } controller.enqueue(value); return;
    }
  }, cancel() { return reader?.cancel(); } });
  return new Response(stream, { status: range ? 206 : 200, headers });
}

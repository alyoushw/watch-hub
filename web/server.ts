// Watch Hub (web): serves the app from ./public and proxies the user's Xtream Codes server.
//
// Browsers on an https page cannot reach most Xtream servers (plain http, no CORS), so the
// page sends those requests through /proxy?url=… . HLS playlists are rewritten so every
// segment also goes through the proxy. Runs on Deno anywhere (Render, Docker, your own machine).
//
// Optional environment variables:
//   PROXY_KEY    a password the page must send (set it in the app's settings too)
//   PROXY_HOSTS  comma-separated hosts the proxy may reach, e.g. "example.com:8080"
import { serveDir } from "jsr:@std/http@1.0.10/file-server";

const PROXY_KEY = Deno.env.get("PROXY_KEY") ?? "";
const PROXY_HOSTS = (Deno.env.get("PROXY_HOSTS") ?? "")
  .split(",").map((h) => h.trim().toLowerCase()).filter(Boolean);
const PUBLIC_DIR = new URL("./public", import.meta.url).pathname;

const CORS: Record<string, string> = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET, HEAD, OPTIONS",
  "access-control-allow-headers": "range, content-type",
  "access-control-expose-headers": "content-length, content-range, accept-ranges, content-type",
};
const VIDEO_TYPES: Record<string, string> = {
  mp4: "video/mp4",
  m4v: "video/mp4",
  mov: "video/quicktime",
  mkv: "video/x-matroska",
  ts: "video/mp2t",
  avi: "video/x-msvideo",
};
const PASS_HEADERS = ["content-type", "content-length", "content-range", "accept-ranges", "last-modified", "etag"];

function fail(status: number, message: string): Response {
  return new Response(message, { status, headers: { ...CORS, "content-type": "text/plain; charset=utf-8" } });
}

async function proxy(req: Request, self: URL): Promise<Response> {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
  if (PROXY_KEY && self.searchParams.get("key") !== PROXY_KEY) return fail(401, "wrong proxy key");

  let target: URL;
  try {
    target = new URL(self.searchParams.get("url") ?? "");
  } catch {
    return fail(400, "missing or bad url");
  }
  if (target.protocol !== "http:" && target.protocol !== "https:") return fail(400, "only http(s)");
  if (PROXY_HOSTS.length && !PROXY_HOSTS.includes(target.host.toLowerCase()) &&
    !PROXY_HOSTS.includes(target.hostname.toLowerCase())) {
    return fail(403, "host not allowed");
  }

  const headers = new Headers({ "user-agent": "Mozilla/5.0 (WatchHub web)" });
  const range = req.headers.get("range");
  if (range) headers.set("range", range);

  let upstream: Response;
  try {
    upstream = await fetch(target, { headers, redirect: "follow", signal: req.signal });
  } catch (e) {
    return fail(502, `could not reach server: ${e instanceof Error ? e.message : e}`);
  }

  const out = new Headers(CORS);
  for (const h of PASS_HEADERS) {
    const v = upstream.headers.get(h);
    if (v) out.set(h, v);
  }

  const finalUrl = upstream.url || target.href;
  const type = upstream.headers.get("content-type") ?? "";
  // Safari will not play an MP4 sent as "application/octet-stream", which many panels do.
  if (!type || /octet-stream|text\/plain/i.test(type)) {
    const ext = target.pathname.split(".").pop()?.toLowerCase() ?? "";
    const named = VIDEO_TYPES[ext];
    if (named) out.set("content-type", named);
  }
  if (/mpegurl/i.test(type) || /\.m3u8?(\?|$)/i.test(new URL(finalUrl).pathname)) {
    const text = await upstream.text();
    out.delete("content-length");
    out.set("content-type", "application/vnd.apple.mpegurl");
    out.set("cache-control", "no-store");
    return new Response(rewritePlaylist(text, finalUrl, self), { status: upstream.status, headers: out });
  }
  return new Response(upstream.body, { status: upstream.status, headers: out });
}

/** Points every URL in an HLS playlist (segments, keys, sub-playlists) back at this proxy. */
function rewritePlaylist(text: string, base: string, self: URL): string {
  const wrap = (u: string) => {
    const abs = new URL(u, base).href;
    const key = PROXY_KEY ? `&key=${encodeURIComponent(PROXY_KEY)}` : "";
    return `${self.origin}/proxy?url=${encodeURIComponent(abs)}${key}`;
  };
  return text.split("\n").map((line) => {
    const l = line.trim();
    if (!l) return line;
    if (l.startsWith("#")) return line.replace(/URI="([^"]+)"/g, (_m, u) => `URI="${wrap(u)}"`);
    return wrap(l);
  }).join("\n");
}

// Hosts like Render pass the port to listen on in $PORT.
const port = Number(Deno.env.get("PORT") ?? 8000);

Deno.serve({ port }, (req) => {
  const url = new URL(req.url);
  if (url.pathname === "/proxy") return proxy(req, url);
  return serveDir(req, { fsRoot: PUBLIC_DIR, quiet: true });
});

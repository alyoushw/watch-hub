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
  "access-control-allow-methods": "GET, HEAD, POST, OPTIONS",
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

// ---------- Watch Hub account (Google sign-in + sync through Firebase) ----------
//
// Set on the host (Render › Environment). Only the public values are ever sent to devices;
// GOOGLE_TV_CLIENT_SECRET stays on this server.
//   FIREBASE_API_KEY, FIREBASE_PROJECT_ID, FIREBASE_APP_ID (web app), FIREBASE_ANDROID_APP_ID,
//   GOOGLE_WEB_CLIENT_ID, GOOGLE_TV_CLIENT_ID, GOOGLE_TV_CLIENT_SECRET
const env = (k: string) => (Deno.env.get(k) ?? "").trim();

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...CORS, "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

/** Public settings every device (phone, TV, browser) reads to sign in and sync. */
function config(): Response {
  const projectId = env("FIREBASE_PROJECT_ID");
  const apiKey = env("FIREBASE_API_KEY");
  if (!projectId || !apiKey) return json({ enabled: false });
  return json({
    enabled: true,
    firebase: {
      apiKey,
      projectId,
      appId: env("FIREBASE_APP_ID"),
      androidAppId: env("FIREBASE_ANDROID_APP_ID") || env("FIREBASE_APP_ID"),
      authDomain: `${projectId}.firebaseapp.com`,
    },
    googleWebClientId: env("GOOGLE_WEB_CLIENT_ID"),
    deviceLogin: Boolean(env("GOOGLE_TV_CLIENT_ID") && env("GOOGLE_TV_CLIENT_SECRET")),
  });
}

/**
 * Sign-in with a code, for TVs (and any device): Google's "limited input device" flow.
 * start → the device shows user_code and google.com/device; poll → once approved, an ID token.
 */
async function deviceStart(): Promise<Response> {
  const clientId = env("GOOGLE_TV_CLIENT_ID");
  if (!clientId) return json({ error: "not_configured" }, 501);
  const res = await fetch("https://oauth2.googleapis.com/device/code", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ client_id: clientId, scope: "openid email profile" }),
  });
  const data = await res.json();
  if (!res.ok) return json({ error: data.error || "google_error" }, 502);
  return json({
    deviceCode: data.device_code,
    userCode: data.user_code,
    verificationUrl: data.verification_url,
    expiresIn: data.expires_in,
    interval: data.interval,
  });
}

async function devicePoll(req: Request): Promise<Response> {
  const clientId = env("GOOGLE_TV_CLIENT_ID");
  const secret = env("GOOGLE_TV_CLIENT_SECRET");
  if (!clientId || !secret) return json({ error: "not_configured" }, 501);
  let deviceCode = "";
  try {
    deviceCode = String((await req.json()).deviceCode || "");
  } catch { /* bad body */ }
  if (!deviceCode) return json({ status: "error" }, 400);
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: clientId,
      client_secret: secret,
      device_code: deviceCode,
      grant_type: "urn:ietf:params:oauth:grant-type:device_code",
    }),
  });
  const data = await res.json();
  if (res.ok && data.id_token) return json({ status: "ok", idToken: data.id_token });
  const map: Record<string, string> = {
    authorization_pending: "pending",
    slow_down: "slow_down",
    access_denied: "denied",
    expired_token: "expired",
  };
  return json({ status: map[data.error] || "error" });
}

Deno.serve({ port }, (req) => {
  const url = new URL(req.url);
  if (url.pathname === "/proxy") return proxy(req, url);
  if (req.method === "OPTIONS" && url.pathname.startsWith("/auth/")) return new Response(null, { status: 204, headers: CORS });
  if (url.pathname === "/config.json") return config();
  if (url.pathname === "/auth/device/start" && req.method === "POST") return deviceStart();
  if (url.pathname === "/auth/device/poll" && req.method === "POST") return devicePoll(req);
  return serveDir(req, { fsRoot: PUBLIC_DIR, quiet: true });
});
